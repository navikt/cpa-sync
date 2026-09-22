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

            nfsConnector.use { connector ->
                val nfsCpaMap = getNfsCpaMap(connector)
                log.info("Found ${nfsCpaMap.size} CPAs in NFS. Found ${dbCpaMap.size} CPAs in DB.")

                if (nfsCpaMap.isEmpty()) {
                    log.warn("No CPAs found in NFS. This is odd.")
                    return@use
                }

                var upserted = 0
                val liveCpaIds = mutableSetOf<String>()
                val unverifiedCpa = mutableListOf<NfsCpa>()

                nfsCpaMap.forEach { entry ->
                    if (syncCpa(connector, entry.key, entry.value, dbCpaMap, liveCpaIds, unverifiedCpa)) {
                        upserted++
                    }
                }
                log.info("Upserted $upserted new/modified CPAs.")

                val deleted = deleteStaleCpa(connector, dbCpaMap, liveCpaIds, unverifiedCpa)
                log.info("Deleted $deleted stale CPAs.")
                log.info("Summary: Found ${nfsCpaMap.size} CPAs. Upserted $upserted CPAs and deleted $deleted stale CPAs.")
            }
        }.onFailure {
            logFailure(it)
        }.getOrThrow()
    }

    // Reads, checks and upserts a single CPA so that at most one CPA content is held in memory at a
    // time, regardless of how many CPAs exist on NFS. Returns true if the CPA was upserted.
    // CPAs that are skipped without reading their content are collected in unverifiedCpa, since
    // their real cpaid is not known until the content has been read.
    private suspend fun syncCpa(
        connector: NFSConnector,
        filenameCpaId: String,
        nfsCpa: NfsCpa,
        dbCpaMap: Map<String, String>,
        liveCpaIds: MutableSet<String>,
        unverifiedCpa: MutableList<NfsCpa>
    ): Boolean {
        if (!shouldUpsertCpa(nfsCpa.timestamp, dbCpaMap[filenameCpaId])) {
            log.debug(Markers.append("cpaId", filenameCpaId), "Skipping upsert for unmodified CPA: $filenameCpaId - ${nfsCpa.timestamp}")
            unverifiedCpa.add(nfsCpa)
            return false
        }

        log.info(Markers.append("cpaId", filenameCpaId), "Reading NFS content for new/modified CPA: $filenameCpaId - ${nfsCpa.timestamp}")
        val cpaContent = fetchNfsCpaContent(connector, nfsCpa.filename)

        // The CPA repo keys its entries on the cpaid inside the CPA itself, so the content is the
        // authority on the ID. The filename is only used as a cheap pre-filter above.
        val cpaId = verifyCpaId(cpaContent, nfsCpa)
        if (cpaId == null) {
            liveCpaIds.add(filenameCpaId)
            return false
        }
        registerLiveCpaId(liveCpaIds, cpaId, nfsCpa.filename)

        if (!shouldUpsertCpa(nfsCpa.timestamp, dbCpaMap[cpaId])) {
            log.debug(Markers.append("cpaId", cpaId), "Skipping upsert for unmodified CPA: $cpaId - ${nfsCpa.timestamp}")
            return false
        }

        log.info(Markers.append("cpaId", cpaId), "Upserting new/modified CPA: $cpaId - ${nfsCpa.timestamp}")
        cpaRepoClient.putCPAinCPARepo(cpaContent, nfsCpa.timestamp)
        return true
    }

    private fun verifyCpaId(cpaContent: String, nfsCpa: NfsCpa): String? {
        val cpaId = getCpaIdFromCpaContent(cpaContent)
        if (cpaId == null) {
            log.warn("Regex to find CPA ID in file ${nfsCpa.filename} did not find any match. File corrupted or wrongful regex.")
            return null
        }
        if (cpaId != nfsCpa.id) {
            log.warn(
                Markers.append("cpaId", cpaId),
                "CPA ID in file ${nfsCpa.filename} is $cpaId, which does not match the ID derived from the filename (${nfsCpa.id}). Using the ID from the file content."
            )
        }
        return cpaId
    }

    private fun registerLiveCpaId(liveCpaIds: MutableSet<String>, cpaId: String, filename: String) {
        if (!liveCpaIds.add(cpaId)) {
            log.warn(Markers.append("cpaId", cpaId), "NFS contains duplicate CPA ID $cpaId, last seen in file $filename.")
        }
    }

    internal fun getNfsCpaMap(connector: NFSConnector): Map<String, NfsCpa> {
        return connector.folder().asSequence()
            .filter { entry -> isXmlFileEntry(entry) }
            .fold(mutableMapOf()) { accumulator, nfsCpaFile ->
                val nfsCpa = getNfsCpa(nfsCpaFile) ?: return@fold accumulator

                val existingEntry = accumulator.put(nfsCpa.id, nfsCpa)
                require(existingEntry == null) { "NFS contains duplicate CPA IDs. Aborting sync." }

                accumulator
            }
    }

    internal fun isXmlFileEntry(entry: ChannelSftp.LsEntry): Boolean {
        if (entry.filename.endsWith(".xml")) {
            return true
        }
        log.debug("${entry.filename} is ignored. Invalid file ending")
        return false
    }

    internal fun getNfsCpa(nfsCpaFile: ChannelSftp.LsEntry): NfsCpa? {
        val cpaId = getCpaIdFromFilename(nfsCpaFile.filename)

        if (cpaId == null) {
            log.warn("Could not derive CPA ID from filename ${nfsCpaFile.filename}. Invalid filename format.")
            return null
        }

        val timestamp = getLastModified(nfsCpaFile.attrs.mTime.toLong())

        return NfsCpa(cpaId, timestamp, nfsCpaFile.filename)
    }

    internal fun getCpaIdFromFilename(filename: String): String? {
        if (!filename.endsWith(".xml")) {
            return null
        }
        return filename.removeSuffix(".xml").replace(".", ":").ifBlank { null }
    }

    private fun fetchNfsCpaContent(nfsConnector: NFSConnector, filename: String): String {
        return nfsConnector.file(filename).use {
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

    private suspend fun deleteStaleCpa(
        connector: NFSConnector,
        dbCpaMap: Map<String, String>,
        liveCpaIds: MutableSet<String>,
        unverifiedCpa: List<NfsCpa>
    ): Int {
        // CPAs skipped by the filename fast path never had their content read, so their filename ID
        // is only a guess at the cpaid the CPA repo knows them by. Deleting based on that guess can
        // remove a CPA that an NFS file still provides under a different ID, so confirm the real IDs
        // before deleting anything. This only costs extra reads when something is about to be
        // deleted, which is rare.
        var staleCpa = dbCpaMap - (liveCpaIds + unverifiedCpa.map { it.id })
        if (staleCpa.isNotEmpty()) {
            log.info("Found ${staleCpa.size} CPAs to delete. Verifying CPA IDs of ${unverifiedCpa.size} unread NFS files first.")
            unverifiedCpa.forEach { nfsCpa ->
                val cpaContent = fetchNfsCpaContent(connector, nfsCpa.filename)
                val cpaId = verifyCpaId(cpaContent, nfsCpa)
                if (cpaId == null) {
                    liveCpaIds.add(nfsCpa.id)
                } else {
                    registerLiveCpaId(liveCpaIds, cpaId, nfsCpa.filename)
                }
            }
            staleCpa = dbCpaMap - liveCpaIds
        }

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
