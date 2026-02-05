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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.emottak.cpa.nfs.NFSConnector
import no.nav.emottak.utils.environment.getEnvVar
import no.nav.emottak.utils.environment.isProdEnv
import org.slf4j.LoggerFactory
import kotlin.concurrent.timer

fun main() {
    // if (getEnvVar("NAIS_CLUSTER_NAME", "local") != "prod-fss") {
    DecoroutinatorRuntime.load()
    // }

    val cpaRepoClient = getCpaRepoAuthenticatedClient()
    val activateCpaIntervalSeconds = getEnvVar("ACTIVATE_CPA_INTERVAL_SECONDS", "3600").toLong()
    val syncCpaIntervalSeconds = getEnvVar("SYNC_CPA_INTERVAL_SECONDS", "300").toLong()

    runBlocking {
        launchActivateCpa(
            5,
            activateCpaIntervalSeconds,
            cpaRepoClient
        )
        launchSyncCpa(
            5,
            syncCpaIntervalSeconds,
            cpaRepoClient
        )
    }

    embeddedServer(Netty, port = 8080, module = Application::myApplicationModule).start(wait = true)
}

internal val log = LoggerFactory.getLogger("no.nav.emottak.cpa")

fun Application.myApplicationModule() {
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
        }
        registerHealthEndpoints(appMicrometerRegistry)
        cpaSync()
        activateCpa()
    }
}

fun CoroutineScope.launchSyncCpa(
    startupDelaySeconds: Long,
    processIntervalSeconds: Long,
    cpaRepoClient: HttpClient
) {
    timer(
        name = "Sync CPA Timer",
        initialDelay = startupDelaySeconds * 1000,
        period = processIntervalSeconds * 1000,
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
    startupDelaySeconds: Long,
    processIntervalSeconds: Long,
    cpaRepoClient: HttpClient
) {
    timer(
        name = "Activate CPA Timer",
        initialDelay = startupDelaySeconds * 1000,
        period = processIntervalSeconds * 1000,
        daemon = true
    ) {
        launch(Dispatchers.IO) {
            log.info("----- Running task: Activate CPA")
            try {
                val cpaSyncService = CpaSyncService(cpaRepoClient, NFSConnector())
                cpaSyncService.activatePendingCpas()
                log.info("----- Done: Activate CPA")
            } catch (e: Exception) {
                log.error("Failed task: Activate CPA", e)
            }
        }
    }
}
