package no.nav.emottak.cpa

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import io.ktor.client.HttpClient
import net.logstash.logback.marker.Markers
import no.nav.emottak.cpa.nfs.NFSConnector
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class CpaSyncService(private val cpaRepoClient: HttpClient, private val nfsConnector: NFSConnector) {

    companion object {
        private const val NFS_PROGRESS_LOG_INTERVAL = 100
    }

    suspend fun sync() {
        return runCatching {
            val dbCpaMap = cpaRepoClient.getCPATimestamps()
            log.info("Found ${dbCpaMap.size} CPAs in CPA Repo.")
            val nfsCpaMap = getNfsCpaMap()
            log.info("Found ${nfsCpaMap.size} CPAs in NFS cpaMap.")
            if (nfsCpaMap.isNotEmpty()) {
                val upserted = upsertFreshCpa(nfsCpaMap, dbCpaMap)
                log.info("Upserted $upserted new/modified CPAs.")
                val deleted = deleteStaleCpa(nfsCpaMap.keys, dbCpaMap)
                log.info("Deleted $deleted stale CPAs.")
                log.info("Summary: Found ${nfsCpaMap.size} CPAs. Upserted $upserted CPAs and deleted $deleted stale CPAs.")
            } else {
                log.warn("No CPAs found in NFS. This is odd.")
            }
        }.onFailure {
            logFailure(it)
        }.getOrThrow()
    }

    internal fun getNfsCpaMap(): Map<String, NfsCpa> {
        nfsConnector.use { connector ->
            val xmlFiles = connector.folder().filter { entry -> isXmlFileEntry(entry) }
            log.info("Found ${xmlFiles.size} CPA files on NFS. Starting to read them.")
            var processed = 0

            return xmlFiles.fold(mutableMapOf()) { accumulator, nfsCpaFile ->
                val nfsCpa = getNfsCpa(connector, nfsCpaFile)
                processed++
                if (processed % NFS_PROGRESS_LOG_INTERVAL == 0 || processed == xmlFiles.size) {
                    log.info("Read $processed of ${xmlFiles.size} CPA files from NFS.")
                }
                if (nfsCpa == null) return@fold accumulator

                val existingEntry = accumulator.put(nfsCpa.id, nfsCpa)
                require(existingEntry == null) { "NFS contains duplicate CPA IDs. Aborting sync." }

                accumulator
            }
        }
    }

    internal fun isXmlFileEntry(entry: ChannelSftp.LsEntry): Boolean {
        if (entry.filename.endsWith(".xml")) {
            return true
        }
        log.debug("${entry.filename} is ignored. Invalid file ending")
        return false
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
        return nfsConnector.file("${nfsCpaFile.filename}").use {
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

    private suspend fun upsertFreshCpa(nfsCpaMap: Map<String, NfsCpa>, dbCpaMap: Map<String, String>): Int {
        var upsertCount = 0
        nfsCpaMap.forEach { entry ->
            if (shouldUpsertCpa(entry.value.timestamp, dbCpaMap[entry.key])) {
                log.info(Markers.append("cpaId", entry.key), "Upserting new/modified CPA: ${entry.key} - ${entry.value.timestamp}")
                val unzippedCpaContent = unzipCpaContent(entry.value.content)
                cpaRepoClient.putCPAinCPARepo(unzippedCpaContent, entry.value.timestamp)
                upsertCount++
            } else {
                log.debug(Markers.append("cpaId", entry.key), "Skipping upsert for unmodified CPA: ${entry.key} - ${entry.value.timestamp}")
            }
        }
        return upsertCount
    }

    internal fun unzipCpaContent(byteArray: ByteArray): String {
        return GZIPInputStream(byteArray.inputStream()).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    internal fun shouldUpsertCpa(nfsTimestamp: String, dbTimestamp: String?): Boolean {
        return dbTimestamp == null || Instant.parse(nfsTimestamp) > Instant.parse(dbTimestamp)
    }

    private suspend fun deleteStaleCpa(nfsCpaIds: Set<String>, dbCpaMap: Map<String, String>): Int {
        val staleCpa = dbCpaMap - nfsCpaIds
        staleCpa.forEach { entry ->
            log.info(Markers.append("cpaId", entry.key), "Deleting stale entry: ${entry.key} - ${entry.value}")
            cpaRepoClient.deleteCPAinCPARepo(entry.key)
        }
        return staleCpa.size
    }

    internal fun logFailure(throwable: Throwable) {
        when (throwable) {
            is SftpException -> log.error("SftpException ID: [${throwable.id}]", throwable)
            else -> log.error(throwable.message, throwable)
        }
    }
}
