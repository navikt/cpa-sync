package no.nav.emottak.cpa

import com.jcraft.jsch.ChannelSftp
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import no.nav.emottak.cpa.nfs.NFSConnector
import no.nav.emottak.cpa.persistence.CpaArchiveRepository
import no.nav.emottak.utils.environment.getEnvVar
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class NfsCpa(val id: String, val timestamp: String, val content: ByteArray)

// En CPA som skal aktiveres ligger i en fil med filnavn som slutter på angitt suffix og starter med lokal dato og tid (MMddHHmm) for aktivering
const val QUARANTINE_SUFFIX = ".qrntn"
val ACTIVATION_TIMEZONE = ZoneId.of("Europe/Oslo")

val CPA_REFRESH_URL = getEnvVar("CPA_REFRESH_URL", "http://dummy")

class CpaActivateService(private val nfsConnector: NFSConnector, private val cpaArchiveRepository: CpaArchiveRepository, private val emottakAdminClient: HttpClient) {
    private val log: Logger = LoggerFactory.getLogger("no.nav.emottak.smtp.cpasync")

    suspend fun activatePendingCpas() {
        var countActivated = 0
        nfsConnector.use { connector ->
            connector.folder().asSequence()
                .filter { entry -> isFileEntryToBeActivated(entry) }
                .forEach {
                        entry ->
                    activate(connector, entry, cpaArchiveRepository)
                    countActivated++
                }
        }
        // NB: DB-transaksjonen for endringene over må være committet FØR refresh-logikken i admin kjører, ellers får den ikke med seg endringene
        log.info("Activated $countActivated CPA(s)")
        if (countActivated > 0) {
            refreshCache()
        }
    }

    internal fun isFileEntryToBeActivated(entry: ChannelSftp.LsEntry): Boolean {
        // Logger ALLE med dato i navnet, i tilfelle det lages noen med tull i filnavnet, som da aldri vil bli aktivert
        if (startsWithTimestampAndUnderscore(entry.filename)) {
            if (isActivationDue(entry.filename)) {
                log.info("${entry.filename} is due to be activated")
                return true
            } else {
                log.info("${entry.filename} is set up for future activation, but not yet due to be activated")
            }
        }
        return false
    }

    internal fun startsWithTimestampAndUnderscore(filename: String): Boolean {
        if (filename.length < 9 || filename[8] != '_') {
            // Filnavn starter ikke med 8-tegns dato og understreking
            return false
        }
        try {
            val first8CharactersAsInt = filename.substring(0, 8).toInt()
            return true
        } catch (e: Exception) {
            // Filnavn starter ikke med 8-tegns dato
            return false
        }
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

    /*
    Dette skal gjøres for å aktivere en skedulert CPA, som har filnavn med mmDDHHMM-prefix:

    - endre innholdet i den skedulerte fila så CPA-ID inni fila blir riktig (nav:XXX, uten timestamp-prefix)
    - rename den skedulerte fila så den blir hetende nav.XXX.xml (evt med .qrntn hvis eksisterende CPA er i karantene).
      Da overskriver den fila som evt. har det navnet nå.
    - insert'e en record i partner_cpa_archive for cpa-id yyyyMMdd_nav:XXX, med deleted=1, og ny reason
    - insert'e en record i partner_cpa_archive for cpa-id nav:XXX, med deleted = 0, ny partner_cpp_id og mottak_id, ny reason, quarantined false eller som dagens CPA
    - endre record'en i partner_cpa som har cpa-id nav:XXX så den får verdiene fra den nye recorden over, eller inserte hvis det ikke finnes en slik record
    - slette record'en i partner_cpa som har cpa-id yyyyMMdd_nav:XXX
    Fra Parviz: partner_cpp_id og mottak_id fylles ut med f.eks. timestamp eller "cpa-bestilling-oppdatering"
    Eksempel på CPP_ID fra admin: nav.K148586.20260217101115, MOTTAK_ID: 2602160959cppa50771
     */
    enum class ExistingCpaStatus { DOES_NOT_EXIST, IS_IN_QUARANTINE, IS_NOT_IN_QUARANTINE }
    internal suspend fun activate(connector: NFSConnector, entry: ChannelSftp.LsEntry, cpaArchiveRepository: CpaArchiveRepository) {
        try {
            val existingCpaStatus = activateAtFilesystem(connector, entry)
            activateInDb(entry, cpaArchiveRepository, existingCpaStatus)
        } catch (e: Exception) {
            log.error("Failed to activate ${entry.filename}", e)
        }
    }

    internal suspend fun refreshCache() {
        log.info("Refreshing CPA cache in EMOTTAK ADMIN, using url: $CPA_REFRESH_URL")
        val response = emottakAdminClient.refreshCpas(CPA_REFRESH_URL)

        if (response.status != HttpStatusCode.OK) {
            log.warn("Got unexpected HTTP status ${response.status.description} and response ${response.bodyAsText()}")
        } else {
            log.info("CPA refresh was called successfully")
        }
    }

    internal suspend fun activateAtFilesystem(connector: NFSConnector, entry: ChannelSftp.LsEntry): ExistingCpaStatus {
        var activatedCpaFilename = getActivatedName(entry.filename)
        if (activatedCpaFilename == null) {
            throw RuntimeException("Failed to convert ${entry.filename} to activated CPA file name")
        }
        val cpaIdToUse = cpaIdFromFilename(activatedCpaFilename)
        // NB: Kan hende er eksisterende CPA i karantene, dvs filnavnet er ikke så enkelt som <CPA>.xml
        var existingCpaStatus = ExistingCpaStatus.IS_NOT_IN_QUARANTINE
        if (!connector.exists(activatedCpaFilename)) {
            val withoutXmlExtension = activatedCpaFilename.substring(0, activatedCpaFilename.length - 4)
            val filesForThisCpaInQuarantine = connector.filesMatchingName(withoutXmlExtension + "*.qrntn")
            if (filesForThisCpaInQuarantine.isNotEmpty()) {
                activatedCpaFilename = filesForThisCpaInQuarantine.get(0).filename
                existingCpaStatus = ExistingCpaStatus.IS_IN_QUARANTINE
            } else {
                existingCpaStatus = ExistingCpaStatus.DOES_NOT_EXIST
            }
        }
        val fileContents = connector.file(entry.filename).use { fileStream ->
            String(fileStream.readAllBytes())
        }
        val fileContentsWithCorrectCpaId: String = changeCpaIdInFile(fileContents, cpaIdToUse)
        connector.save(entry.filename, ByteArrayInputStream(fileContentsWithCorrectCpaId.toByteArray()))
        connector.rename(entry.filename, activatedCpaFilename)
        log.info("${entry.filename} has been activated with file name $activatedCpaFilename")
        return existingCpaStatus
    }

    //  nav.qass.12345.xml  ->  nav:qass:12345
    internal fun cpaIdFromFilename(filename: String): String {
        return filename.replace(".xml", "").replace(".", ":")
    }

    //  cppa:cpaid="02251213_nav:qass:12345"  ->  cppa:cpaid="nav:qass:12345"
    internal fun changeCpaIdInFile(fileContents: String, cpaId: String): String {
        return fileContents.replace(Regex("cppa:cpaid=\".+?\""), "cppa:cpaid=\"$cpaId\"")
    }

    internal fun getActivatedName(filename: String): String? {
        // Example: 01230800_nav.60120._R_Zm9ybnllbHNl._R_.qrntn  ->  nav.60120.xml
        if (filename.length < 9 || filename[8] != '_') {
            log.warn("$filename does not start with 8-character TS and underscore, cannot be converted to activated name")
            return null
        }
        val fromStart = filename.substring(9)
        var endIndex = fromStart.indexOf("._R_")
        if (endIndex > -1 && endIndex < 8) {
            log.warn("$filename does not contain proper CPA ID between first and second '._R_', cannot be converted to activated name")
            return null
        }
        if (endIndex == -1) {
            // not in quarantine, so use whole filename
            endIndex = fromStart.length
        }
        val cpaId = fromStart.substring(0, endIndex)
        if (cpaId.endsWith(".xml")) {
            return cpaId
        }
        if (cpaId.endsWith(".")) {
            return cpaId + "xml"
        }
        return cpaId + ".xml"
    }

    internal fun getCpaPart(filename: String): String? {
        // Example: 01230800_nav.60120._R_Zm9ybnllbHNl._R_.qrntn  ->  01230800_nav.60120.xml
        if (filename.length < 9 || filename[8] != '_') {
            log.warn("$filename does not start with 8-character TS and underscore, cannot be converted to activated name")
            return null
        }
        var endIndex = filename.indexOf("._R_")
        if (endIndex > -1 && endIndex < 8) {
            log.warn("$filename does not contain proper CPA ID between first and second '._R_', cannot be converted to activated name")
            return null
        }
        if (endIndex == -1) {
            // not in quarantine, so use whole filename
            endIndex = filename.length
        }
        val cpaId = filename.substring(0, endIndex)
        if (cpaId.endsWith(".xml")) {
            return cpaId
        }
        if (cpaId.endsWith(".")) {
            return cpaId + "xml"
        }
        return cpaId + ".xml"
    }

    internal suspend fun activateInDb(entry: ChannelSftp.LsEntry, cpaArchiveRepository: CpaArchiveRepository, existingCpaStatus: ExistingCpaStatus) {
        val activatedCpaFilename = getActivatedName(entry.filename)
        val tmpFilename = getCpaPart(entry.filename)
        if (activatedCpaFilename == null || tmpFilename == null) {
            log.warn("Failed to convert ${entry.filename} to activated CPA file name")
            return
        }
        val cpaId = cpaIdFromFilename(activatedCpaFilename)
        val tmpCpaId = tmpFilename.replace(".xml", "").replace(".", ":")
        log.info("Activating CPA $cpaId from $tmpCpaId")
        val reason = "Activated at specified time"
        val latestTmp = cpaArchiveRepository.findLatestByCpaId(tmpCpaId)
        if (latestTmp != null) {
            // lag ny archive-record med tmpid, endring er deleted = 1, og ny reason
            cpaArchiveRepository.insertCopy(latestTmp.id)
            val copyTmp = cpaArchiveRepository.findLatestByCpaId(tmpCpaId)!!
            cpaArchiveRepository.setDeleted(copyTmp.id, reason)

            // lag ny archive-record med ordentlig id, endringer: deleted = 0, ny partner_cpp_id og mottak_id, ny reason
            val timestampString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
            val cppAndMottaksId = "$timestampString.cpa-aktivering"
            log.info("Creating new CPA record for $cpaId with CPP_ID=$cppAndMottaksId")
            cpaArchiveRepository.insertCopy(latestTmp.id)
            val copy = cpaArchiveRepository.findLatestByCpaId(tmpCpaId)!!
            val existingIsQuarantined = existingCpaStatus == ExistingCpaStatus.IS_IN_QUARANTINE
            cpaArchiveRepository.setAsNewCpa(copy.id, cpaId, existingIsQuarantined, cppAndMottaksId, reason)

            if (existingCpaStatus != ExistingCpaStatus.DOES_NOT_EXIST) {
                // endre gjeldende CPA-record med verdier fra nyeste archive-record
                cpaArchiveRepository.updateFromArchive(cpaId, copy.id)
            } else {
                cpaArchiveRepository.insertFromArchive(cpaId, copy.id)
            }
            // slette midlertidig CPA
            cpaArchiveRepository.deleteTmpCpa(tmpCpaId)
        }
    }
}
