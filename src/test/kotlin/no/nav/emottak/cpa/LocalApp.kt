package no.nav.emottak.cpa

import com.jcraft.jsch.ChannelSftp
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.emottak.cpa.nfs.NFSConnector
import java.io.InputStream
import java.time.Instant
import java.util.Vector
import kotlin.concurrent.timer

val dummyNfsEntries = HashMap<String, ChannelSftp.LsEntry>()
val dummyCpaRepoEntries = HashMap<String, String>()

@OptIn(DelicateCoroutinesApi::class)
fun main() {
    log.info("----- Starting app, setting up DUMMY CPA repo, containing hard coded CPA list")
    val timestampNow = Instant.now()
    val timestamp5minutesAgo = Instant.now().minusSeconds(300)
    dummyCpaRepoEntries.put("nav:qass:12345", timestampNow.toString())
    for (entry in dummyCpaRepoEntries.values) {
        log.info("CPA Repo entry: {}", entry)
    }
    val cpaRepoClient: HttpClient = mockCpaRepo()

    log.info("-----Setting up DUMMY NFS connector, containing hard coded file list")
    dummyNfsEntries.put("nav.qass.12345.xml", mockLsEntry("nav.qass.12345.xml", timestamp5minutesAgo.epochSecond.toInt()))
    dummyNfsEntries.put("dummy12346", mockLsEntry("02201234_nav.qass.12346._whatever.qrntn", timestamp5minutesAgo.epochSecond.toInt()))
    dummyNfsEntries.put("dummy12347", mockLsEntry("02050844_nav.qass.12347._whatever.qrntn", timestamp5minutesAgo.epochSecond.toInt()))
    for (entry in dummyNfsEntries.values) {
        log.info("NFS entry: {}", entry.filename)
    }
    val nfsConnector: NFSConnector = mockNfs()

    val interval = 60L
    log.info("----- Setting up CPA activation task, with interval $interval seconds")
    GlobalScope.launchActivateCpaWithConnector(
        interval,
        cpaRepoClient,
        nfsConnector
    )

    val syncInterval = 40L
    log.info("----- Setting up CPA sync task, with interval $syncInterval seconds")
    GlobalScope.launchSyncCpaWithConnector(
        syncInterval,
        cpaRepoClient,
        nfsConnector
    )

    log.info("----- Starting embedded server at port 8080")
    embeddedServer(Netty, port = 8080, module = Application::myApplicationModule).start(wait = true)
}

fun CoroutineScope.launchActivateCpaWithConnector(
    processIntervalSeconds: Long,
    cpaRepoClient: HttpClient,
    nfsConnector: NFSConnector
) {
    timer(
        name = "Activate CPA Timer",
        initialDelay = 3000L,
        period = processIntervalSeconds * 1000,
        daemon = true
    ) {
        launch(Dispatchers.IO) {
            log.info("----- Running CPA activate")
            try {
                val cpaSyncService = CpaSyncService(cpaRepoClient, nfsConnector)
                cpaSyncService.activatePendingCpas()
            } catch (e: Exception) {
                log.error("Failed to activate CPA", e)
            }
        }
    }
}

fun CoroutineScope.launchSyncCpaWithConnector(
    processIntervalSeconds: Long,
    cpaRepoClient: HttpClient,
    nfsConnector: NFSConnector
) {
    timer(
        name = "Sync CPA Timer",
        initialDelay = 4000L,
        period = processIntervalSeconds * 1000,
        daemon = true
    ) {
        launch(Dispatchers.IO) {
            log.info("----- Running CPA sync")
            val cpaSyncService = CpaSyncService(cpaRepoClient, nfsConnector)
            cpaSyncService.sync()
        }
    }
}

fun mockCpaRepo(): HttpClient {
    val mockCpaRepo: HttpClient = mockk(relaxed = true)
    /*
    Hvis vi ikke har med mockkStatic(), stopper kjøringen av mockCpaRepo() opp i første forsøk på coEvery().
    Virker som den vil kjøre init-koden i HttpClientKt, og aldri blir ferdig med den.
    Hvis vi tar med mockkStatic(), går det bra - da mockes sikkert *alt* i HttpClientKt.
    Men da vil de mockede kallene få med 2 ekstra parametre.
    Første parameter er HttpClient-objektet som funksjonen kalles for, og siste parameter er funksjonen den kalles fra.
    Dvs at når man skal hente ut de reelle parametrene må man hente fra index 1.
     */
    mockkStatic("no.nav.emottak.cpa.HttpClientKt")

    coEvery {
        mockCpaRepo.getCPATimestamps()
    }.answers {
        dummyCpaRepoEntries
    }
    coEvery {
        mockCpaRepo.putCPAinCPARepo(any(), any())
    }.answers {
        // Pga static mocking hentes parametrene ut som nr 2 og 3
        val cpaId = getCpaId(secondArg<String>()) ?: ""
        dummyCpaRepoEntries.put(cpaId, thirdArg<String>())
        mockk()
    }
    coEvery {
        mockCpaRepo.deleteCPAinCPARepo(any())
    }.answers {
        dummyCpaRepoEntries.remove(secondArg<String>())
        mockk()
    }
    return mockCpaRepo
}

fun mockNfs(): NFSConnector {
    val mockNfs: NFSConnector = mockk(relaxed = true)
    coEvery {
        mockNfs.folder()
    }.answers {
        vectorOf(dummyNfsEntries.values.toList())
    }
    coEvery {
        mockNfs.copy(any(), any())
    }.answers {
        dummyNfsEntries.put(secondArg<String>(), mockLsEntry(secondArg<String>(), Instant.now().epochSecond.toInt()))
    }
    coEvery {
        mockNfs.file(any())
    }.answers {
        val filenameWithoutPath = firstArg<String>().substringAfterLast("/")
        // nav.qass.12345.xml -> nav:qass:12345
        val cpaId = filenameWithoutPath.replace(".xml", "").replace(".", ":")
        readFile(cpaId)
    }
    return mockNfs
}

@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS", "RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
private fun readFile(cpaId: String): InputStream {
    var cpaFile = String(ClassLoader.getSystemResourceAsStream("cpa/nav.qass.12345.xml").readAllBytes())
    cpaFile = cpaFile.replace("cppa:cpaid=\"nav:qass:12345\"", "cppa:cpaid=\"" + cpaId + "\"")
    return cpaFile.byteInputStream()
}

fun vectorOf(nfsEntries: List<ChannelSftp.LsEntry>): Vector<ChannelSftp.LsEntry> {
    val vector = Vector<ChannelSftp.LsEntry>()
    vector.addAll(nfsEntries)
    return vector
}

fun mockLsEntry(filename: String, mTime: Int): ChannelSftp.LsEntry {
    val mockEntry = mockk<ChannelSftp.LsEntry>()
    coEvery { mockEntry.filename }.returns(filename)
    coEvery { mockEntry.attrs.mTime }.returns(mTime)
    return mockEntry
}
private fun getCpaId(cpaContent: String): String? {
    return Regex("cpaid=\"(?<cpaId>.+?)\"")
        .find(cpaContent)?.groups?.get("cpaId")?.value
}
