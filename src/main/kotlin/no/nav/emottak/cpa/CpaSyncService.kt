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
            // The connector is connected on construction, so it has to be closed even if fetching
            // the CPA repo timestamps fails.
            nfsConnector.use { connector ->
                val dbCpaMap = cpaRepoClient.getCPATimestamps()
                val nfsCpaMap = getNfsCpaMap(connector)
                log.info("Found ${nfsCpaMap.size} CPAs in NFS. Found ${dbCpaMap.size} CPAs in DB.")

                if (nfsCpaMap.isEmpty()) {
                    log.warn("No CPAs found in NFS. This is odd.")
                    return@use
                }

                var upserted = 0
                val state = SyncState()

                nfsCpaMap.forEach { entry ->
                    if (syncCpa(connector, entry.key, entry.value, dbCpaMap, state)) {
                        upserted++
                    }
                }
                log.info("Upserted $upserted new/modified CPAs.")

                val staleCpa = resolveStaleCpa(connector, dbCpaMap, state)
                // Duplicate cpaids make the live-ID set unreliable, so fail before deleting
                // anything. Deletions are the only irreversible part of a sync.
                requireNoDuplicateCpaIds(state)

                val deleted = deleteStaleCpa(staleCpa)
                log.info("Deleted $deleted stale CPAs.")
                log.info("Summary: Found ${nfsCpaMap.size} CPAs. Upserted $upserted CPAs and deleted $deleted stale CPAs.")
            }
        }.onFailure {
            logFailure(it)
        }.getOrThrow()
    }

    // Per-sync bookkeeping.
    // liveCpaIds protects CPAs from deletion and therefore also holds conservative, unconfirmed
    // guesses. declaredCpaIds holds only IDs actually declared by CPA content, so the two must not
    // be conflated: a guess must never be able to trigger duplicate detection.
    private class SyncState {
        val liveCpaIds = mutableSetOf<String>()
        val declaredCpaIds = mutableSetOf<String>()
        val duplicateCpaIds = mutableSetOf<String>()
        val unverifiedCpa = mutableListOf<NfsCpa>()
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
        state: SyncState
    ): Boolean {
        if (isUnmodifiedCpa(nfsCpa.timestamp, dbCpaMap[filenameCpaId])) {
            log.debug(Markers.append("cpaId", filenameCpaId), "Skipping upsert for unmodified CPA: $filenameCpaId - ${nfsCpa.timestamp}")
            state.unverifiedCpa.add(nfsCpa)
            return false
        }

        log.info(Markers.append("cpaId", filenameCpaId), "Reading NFS content for new/modified CPA: $filenameCpaId - ${nfsCpa.timestamp}")
        val cpaContent = fetchNfsCpaContent(connector, nfsCpa.filename)

        // The CPA repo keys its entries on the cpaid inside the CPA itself, so the content is the
        // authority on the ID. The filename is only used as a cheap pre-filter above, and only
        // when the repo timestamp confirms that the entry came from this very file.
        val cpaId = verifyCpaId(cpaContent, nfsCpa)
        if (cpaId == null) {
            protectFromDeletion(state, filenameCpaId)
            return false
        }
        if (!registerDeclaredCpaId(state, cpaId, nfsCpa.filename)) {
            return false
        }

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

    // Keeps a CPA ID out of the delete pass without claiming that any file declared it. Used when
    // the cpaid of a file could not be read, where deleting on the filename guess is the only
    // unrecoverable outcome.
    private fun protectFromDeletion(state: SyncState, cpaId: String) {
        state.liveCpaIds.add(cpaId)
    }

    // Two files declaring the same cpaid means the CPA repo content for that ID depends on the
    // order NFS happens to list the files in. The first file has already been written by the time
    // the second one is read, so skip the upsert here and let the sync fail once the remaining CPAs
    // have been processed, rather than aborting half way through with deletions still pending.
    private fun registerDeclaredCpaId(state: SyncState, cpaId: String, filename: String): Boolean {
        state.liveCpaIds.add(cpaId)
        if (!state.declaredCpaIds.add(cpaId)) {
            state.duplicateCpaIds.add(cpaId)
            log.error(
                Markers.append("cpaId", cpaId),
                "File $filename declares CPA ID $cpaId, which is already declared by another NFS file. Skipping upsert of $filename."
            )
            return false
        }
        return true
    }

    private fun requireNoDuplicateCpaIds(state: SyncState) {
        require(state.duplicateCpaIds.isEmpty()) {
            "NFS contains duplicate CPA IDs. Aborting sync. Duplicate CPA IDs: ${state.duplicateCpaIds.sorted().joinToString()}."
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

    // Only an exact timestamp match proves that the CPA repo entry was created from this very file,
    // since the NFS timestamp is stored verbatim on upsert. A repo entry that is merely newer may
    // belong to a different file, so its CPA content has to be read and its cpaid verified.
    internal fun isUnmodifiedCpa(nfsTimestamp: String, dbTimestamp: String?): Boolean {
        return dbTimestamp != null && Instant.parse(nfsTimestamp) == Instant.parse(dbTimestamp)
    }

    private fun resolveStaleCpa(
        connector: NFSConnector,
        dbCpaMap: Map<String, String>,
        state: SyncState
    ): Map<String, String> {
        // CPAs skipped by the filename fast path never had their content read, so their filename ID
        // is only a guess at the cpaid the CPA repo knows them by. Deleting based on that guess can
        // remove a CPA that an NFS file still provides under a different ID, so confirm the real IDs
        // before deleting anything. This only costs extra reads when something is about to be
        // deleted, which is rare.
        val staleCpa = dbCpaMap - (state.liveCpaIds + state.unverifiedCpa.map { it.id })
        if (staleCpa.isEmpty()) {
            return staleCpa
        }

        log.info("Found ${staleCpa.size} CPAs to delete. Verifying CPA IDs of ${state.unverifiedCpa.size} unread NFS files first.")
        state.unverifiedCpa.forEach { nfsCpa ->
            val cpaContent = fetchNfsCpaContent(connector, nfsCpa.filename)
            val cpaId = verifyCpaId(cpaContent, nfsCpa)
            if (cpaId == null) {
                protectFromDeletion(state, nfsCpa.id)
            } else {
                registerDeclaredCpaId(state, cpaId, nfsCpa.filename)
            }
        }
        return dbCpaMap - state.liveCpaIds
    }

    private suspend fun deleteStaleCpa(staleCpa: Map<String, String>): Int {
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
