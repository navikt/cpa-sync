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

    suspend fun sync() {
        return runCatching {
            val dbCpaMap = cpaRepoClient.getCPATimestamps()
            val syncResult = syncNfsCpaStream(dbCpaMap)
            if (syncResult.processedIds.isNotEmpty()) {
                val deleted = deleteStaleCpa(syncResult.processedIds, dbCpaMap)
                log.info("Found ${syncResult.processedIds.size} CPAs. Upserted ${syncResult.upsertCount} CPAs and deleted $deleted stale CPAs.")
            } else {
                log.warn("No CPAs found in NFS. This is odd.")
            }
        }.onFailure {
            logFailure(it)
        }.getOrThrow()
    }

    // Streams through NFS files one at a time (instead of loading all CPA contents into memory first) to keep
    // peak memory usage low, since the NFS folder can contain a large number of CPAs.
    private suspend fun syncNfsCpaStream(dbCpaMap: Map<String, String>): NfsSyncResult {
        nfsConnector.use { connector ->
            val processedIds = mutableSetOf<String>()
            var upsertCount = 0

            connector.folder().asSequence()
                .filter { entry -> isXmlFileEntry(entry) }
                .forEach { nfsCpaFile ->
                    val timestamp = getLastModified(nfsCpaFile.attrs.mTime.toLong())
                    val cpaContent = fetchNfsCpaContent(connector, nfsCpaFile)
                    val cpaId = getCpaIdFromCpaContent(cpaContent)

                    if (cpaId == null) {
                        log.warn("Regex to find CPA ID in file ${nfsCpaFile.filename} did not find any match. File corrupted or wrongful regex.")
                        return@forEach
                    }

                    require(processedIds.add(cpaId)) { "NFS contains duplicate CPA IDs. Aborting sync." }

                    if (shouldUpsertCpa(timestamp, dbCpaMap[cpaId])) {
                        log.info(Markers.append("cpaId", cpaId), "Upserting new/modified CPA: $cpaId - $timestamp")
                        cpaRepoClient.putCPAinCPARepo(cpaContent, timestamp)
                        upsertCount++
                    } else {
                        log.debug(Markers.append("cpaId", cpaId), "Skipping upsert for unmodified CPA: $cpaId - $timestamp")
                    }
                }

            return NfsSyncResult(processedIds, upsertCount)
        }
    }

    private data class NfsSyncResult(val processedIds: Set<String>, val upsertCount: Int)

    internal fun getNfsCpaMap(): Map<String, NfsCpa> {
        nfsConnector.use { connector ->
            return connector.folder().asSequence()
                .filter { entry -> isXmlFileEntry(entry) }
                .fold(mutableMapOf()) { accumulator, nfsCpaFile ->
                    val nfsCpa = getNfsCpa(connector, nfsCpaFile) ?: return@fold accumulator

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
