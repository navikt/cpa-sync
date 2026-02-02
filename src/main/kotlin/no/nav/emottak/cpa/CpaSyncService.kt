package no.nav.emottak.cpa

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import io.ktor.client.HttpClient
import net.logstash.logback.marker.Markers
import no.nav.emottak.cpa.nfs.NFSConnector
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class NfsCpa(val id: String, val timestamp: String, val content: ByteArray)

// En CPA som skal aktiveres ligger i en fil med filnavn som slutter på angitt suffix og starter med lokal dato og tid (MMddHHmm) for aktivering
const val QUARANTINE_SUFFIX = ".qrntn"
val ACTIVATION_TIMEZONE = ZoneId.of("Europe/Oslo")

class CpaSyncService(private val cpaRepoClient: HttpClient, private val nfsConnector: NFSConnector) {
    private val log: Logger = LoggerFactory.getLogger("no.nav.emottak.smtp.cpasync")

    suspend fun sync() {
        return runCatching {
            val dbCpaMap = cpaRepoClient.getCPATimestamps()
            val nfsCpaMap = getNfsCpaMap()
            upsertFreshCpa(nfsCpaMap, dbCpaMap)
            deleteStaleCpa(nfsCpaMap.keys, dbCpaMap)
        }.onFailure {
            logFailure(it)
        }.getOrThrow()
    }

    internal fun getNfsCpaMap(): Map<String, NfsCpa> {
        nfsConnector.use { connector ->
            return connector.folder().asSequence()
                .filter { entry -> isXmlFileEntry(entry) }
                .fold(mutableMapOf<String, NfsCpa>()) { accumulator, nfsCpaFile ->
                    val nfsCpa = getNfsCpa(connector, nfsCpaFile) ?: return accumulator

                    val existingEntry = accumulator.put(nfsCpa.id, nfsCpa)
                    require(existingEntry == null) { "NFS contains duplicate CPA IDs. Aborting sync." }

                    accumulator
                }
        }
    }

    suspend fun activatePendingCpas() {
        nfsConnector.use { connector ->
            connector.folder().asSequence()
                .filter { entry -> isFileEntryToBeActivated(entry) }
                .forEach { entry -> activate(connector, entry) }
        }
    }

    internal fun isXmlFileEntry(entry: ChannelSftp.LsEntry): Boolean {
        if (entry.filename.endsWith(".xml")) {
            return true
        }
        log.debug("${entry.filename} is ignored. Invalid file ending")
        return false
    }

    internal fun isFileEntryToBeActivated(entry: ChannelSftp.LsEntry): Boolean {
        // Logger ALLE med quarantine-suffix, i tilfelle det lages noen med tull i filnavnet, som da aldri vil bli aktivert
        if (entry.filename.endsWith(QUARANTINE_SUFFIX)) {
            if (isActivationDue(entry.filename)) {
                log.info("${entry.filename} is due to be activated")
                return true
            } else {
                log.info("${entry.filename} is in quarantine, but not yet due to be activated")
            }
        }
        return false
    }

    internal fun isActivationDue(filename: String): Boolean {
        if (filename.length < 9 || filename[8] != '_') {
            // Filnavn starter ikke med 8-tegns dato og understreking
            return false
        }
        try {
            val activationMonth = filename.substring(0, 2).toInt()
            val activationDayOfMonth = filename.substring(2, 4).toInt()
            val today = Instant.now().atZone(ACTIVATION_TIMEZONE).toLocalDate()
            if (activationMonth != today.monthValue || activationDayOfMonth != today.dayOfMonth) {
                return false
            }
            // Skal aktiveres idag, sjekk om tidspunktet er passert
            val activationHour = filename.substring(4, 6).toInt()
            val activationMinute = filename.substring(6, 8).toInt()
            val now = Instant.now().atZone(ACTIVATION_TIMEZONE).toLocalTime().withSecond(1)
            return !now.isBefore(LocalTime.of(activationHour, activationMinute).withSecond(0))
        } catch (e: Exception) {
            // Filnavn starter ikke med 8-tegns dato
            return false
        }
    }

    internal suspend fun activate(connector: NFSConnector, entry: ChannelSftp.LsEntry) {
        val activatedCpaFilename = getActivatedName(entry.filename)
        if (activatedCpaFilename == null) {
            log.warn("Failed to convert ${entry.filename} to activated CPA file name")
            return
        }
        try {
            connector.copy(entry.filename, activatedCpaFilename)
            log.info("${entry.filename} has been activated with file name $activatedCpaFilename")
        } catch (e: Exception) {
            log.error("Failed to activate ${entry.filename}", e)
        }
    }

    internal fun getActivatedName(filename: String): String? {
        // Example: 01230800_nav.60120._R_Zm9ybnllbHNl._R_.qrntn  ->  nav.60120.xml
        if (filename.length < 9 || filename[8] != '_') {
            log.warn("$filename does not start with 8-character TS and underscore, cannot be converted to activated name")
            return null
        }
        val fromStart = filename.substring(9)
        val endIndex = fromStart.indexOf('_')
        if (endIndex == -1 || endIndex < 8) {
            log.warn("$filename does not contain proper CPA ID between first and second underscore, cannot be converted to activated name")
            return null
        }
        val cpaId = fromStart.substring(0, endIndex)
        if (cpaId.endsWith(".")) {
            return cpaId + "xml"
        }
        return cpaId + ".xml"
    }

    internal fun getNfsCpa(connector: NFSConnector, nfsCpaFile: ChannelSftp.LsEntry): NfsCpa? {
        val timestamp = getLastModified(nfsCpaFile.attrs.mTime.toLong())
        val cpaContent = fetchNfsCpaContent(connector, nfsCpaFile)
        val cpaId = getCpaIdFromCpaContent(cpaContent)

        if (cpaId == null) {
            log.warn("Regex to find CPA ID in file ${nfsCpaFile.filename} did not find any match. File corrupted or wrongful regex.")
            return null
        }

        val zippedCpaContent = zipCpaContent(cpaContent)

        return NfsCpa(cpaId, timestamp, zippedCpaContent)
    }

    private fun fetchNfsCpaContent(nfsConnector: NFSConnector, nfsCpaFile: ChannelSftp.LsEntry): String {
        return nfsConnector.file("/outbound/cpa/${nfsCpaFile.filename}").use {
            String(it.readAllBytes())
        }
    }

    private fun getCpaIdFromCpaContent(cpaContent: String): String? {
        return Regex("cpaid=\"(?<cpaId>.+?)\"")
            .find(cpaContent)?.groups?.get("cpaId")?.value
    }

    internal fun getLastModified(mTimeInSeconds: Long): String {
        return Instant.ofEpochSecond(mTimeInSeconds).truncatedTo(ChronoUnit.SECONDS).toString()
    }

    internal fun zipCpaContent(cpaContent: String): ByteArray {
        val byteStream = ByteArrayOutputStream()
        GZIPOutputStream(byteStream)
            .bufferedWriter(StandardCharsets.UTF_8)
            .use { it.write(cpaContent) }
        return byteStream.toByteArray()
    }

    private suspend fun upsertFreshCpa(nfsCpaMap: Map<String, NfsCpa>, dbCpaMap: Map<String, String>) {
        nfsCpaMap.forEach { entry ->
            if (shouldUpsertCpa(entry.value.timestamp, dbCpaMap[entry.key])) {
                log.info(Markers.append("cpaId", entry.key), "Upserting new/modified CPA: ${entry.key} - ${entry.value.timestamp}")
                val unzippedCpaContent = unzipCpaContent(entry.value.content)
                cpaRepoClient.putCPAinCPARepo(unzippedCpaContent, entry.value.timestamp)
            } else {
                log.debug("Skipping upsert for unmodified CPA: ${entry.key} - ${entry.value.timestamp}")
            }
        }
    }

    internal fun unzipCpaContent(byteArray: ByteArray): String {
        return GZIPInputStream(byteArray.inputStream()).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    internal fun shouldUpsertCpa(nfsTimestamp: String, dbTimestamp: String?): Boolean {
        if (dbTimestamp == null) return true
        return Instant.parse(nfsTimestamp) > Instant.parse(dbTimestamp)
    }

    private suspend fun deleteStaleCpa(nfsCpaIds: Set<String>, dbCpaMap: Map<String, String>) {
        val staleCpa = dbCpaMap - nfsCpaIds
        staleCpa.forEach { entry ->
            log.info(Markers.append("cpaId", entry.key), "Deleting stale entry: ${entry.key} - ${entry.value}")
            cpaRepoClient.deleteCPAinCPARepo(entry.key)
        }
    }

    internal fun logFailure(throwable: Throwable) {
        when (throwable) {
            is SftpException -> log.error("SftpException ID: [${throwable.id}]", throwable)
            else -> log.error(throwable.message, throwable)
        }
    }
}
