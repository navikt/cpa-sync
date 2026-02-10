package no.nav.emottak.cpa

import dev.reformator.stacktracedecoroutinator.runtime.DecoroutinatorRuntime
import io.ktor.client.HttpClient
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.emottak.cpa.nfs.NFSConnector
import no.nav.emottak.cpa.persistence.CpaArchiveRepository
import no.nav.emottak.cpa.persistence.DatabaseConfig
import no.nav.emottak.cpa.persistence.configureCpaArchiveRepository
import no.nav.emottak.utils.environment.getEnvVar
import no.nav.emottak.utils.environment.isProdEnv
import org.slf4j.LoggerFactory
import kotlin.concurrent.timer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class)
fun main() {
    // if (getEnvVar("NAIS_CLUSTER_NAME", "local") != "prod-fss") {
    DecoroutinatorRuntime.load()
    // }

    val cpaRepoClient = getCpaRepoAuthenticatedClient()
    val activateCpaInterval = Duration.parse(getEnvVar("ACTIVATE_CPA_INTERVAL", "1h"))
    val syncCpaInterval = Duration.parse(getEnvVar("SYNC_CPA_INTERVAL", "5m"))

    // Cannot get the vault way to work, use secret with values copied from vault
    val dbConfig = DatabaseConfig(
        jdbcUrl = getEnvVar("EMOTTAK_JDBC_URL"),
        vaultMountPath = "/var/run/secrets/nais.io/dbcreds"
    )
    val cpaArchiveRepository = configureCpaArchiveRepository(dbConfig)

    if (cpaArchiveRepository != null) {
        GlobalScope.launchActivateCpa(
            5.seconds,
            activateCpaInterval,
            cpaArchiveRepository
        )
    }
    GlobalScope.launchSyncCpa(
        5.seconds,
        syncCpaInterval,
        cpaRepoClient
    )

    embeddedServer(Netty, port = 8080, module = myApplicationModule(cpaArchiveRepository)).start(wait = true)
}

internal val log = LoggerFactory.getLogger("no.nav.emottak.cpa")

fun myApplicationModule(cpaArchiveRepository: CpaArchiveRepository?): Application.() -> Unit {
    return {
        install(ContentNegotiation) {
            json()
        }
        val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        install(MicrometerMetrics) {
            registry = appMicrometerRegistry
        }
        routing {
            if (!isProdEnv()) {
                testAzureAuthToCpaRepo()
                if (cpaArchiveRepository != null) testDbRead(cpaArchiveRepository)
            }
            registerHealthEndpoints(appMicrometerRegistry)
            cpaSync()
            if (cpaArchiveRepository != null) activateCpa(cpaArchiveRepository)
        }
    }
}

fun CoroutineScope.launchSyncCpa(
    startupDelay: Duration,
    processInterval: Duration,
    cpaRepoClient: HttpClient
) {
    timer(
        name = "Sync CPA Timer",
        initialDelay = startupDelay.inWholeMilliseconds,
        period = processInterval.inWholeMilliseconds,
        daemon = true
    ) {
        launch(Dispatchers.IO) {
            log.info("----- Running task: Sync CPA")
            try {
                val cpaSyncService = CpaSyncService(cpaRepoClient, NFSConnector())
                cpaSyncService.sync()
                log.info("----- Done: Sync CPA")
            } catch (e: Exception) {
                log.error("Failed task: Sync CPA", e)
            }
        }
    }
}

fun CoroutineScope.launchActivateCpa(
    startupDelay: Duration,
    processInterval: Duration,
    cpaArchiveRepository: CpaArchiveRepository
) {
    timer(
        name = "Activate CPA Timer",
        initialDelay = startupDelay.inWholeMilliseconds,
        period = processInterval.inWholeMilliseconds,
        daemon = true
    ) {
        launch(Dispatchers.IO) {
            log.info("----- Running task: Activate CPA")
            try {
                val cpaActivateService = CpaActivateService(NFSConnector(), cpaArchiveRepository)
                cpaActivateService.activatePendingCpas()
                log.info("----- Done: Activate CPA")
            } catch (e: Exception) {
                log.error("Failed task: Activate CPA", e)
            }
        }
    }
}
