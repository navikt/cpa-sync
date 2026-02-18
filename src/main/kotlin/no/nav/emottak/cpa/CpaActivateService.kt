package no.nav.emottak.cpa

import com.jcraft.jsch.ChannelSftp
import no.nav.emottak.cpa.nfs.NFSConnector
import no.nav.emottak.cpa.persistence.CpaArchiveRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class NfsCpa(val id: String, val timestamp: String, val content: ByteArray)

// En CPA som skal aktiveres ligger i en fil med filnavn som slutter på angitt suffix og starter med lokal dato og tid (MMddHHmm) for aktivering
const val QUARANTINE_SUFFIX = ".qrntn"
val ACTIVATION_TIMEZONE = ZoneId.of("Europe/Oslo")

class CpaActivateService(private val nfsConnector: NFSConnector, private val cpaArchiveRepository: CpaArchiveRepository) {
    private val log: Logger = LoggerFactory.getLogger("no.nav.emottak.smtp.cpasync")

    suspend fun activatePendingCpas() {
        nfsConnector.use { connector ->
            connector.folder().asSequence()
                .filter { entry -> isFileEntryToBeActivated(entry) }
                .forEach { entry -> activate(connector, entry, cpaArchiveRepository) }
        }
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

/*
Da ser det ut til at dette skal gjøres for å aktivere en CPA:

- rename qrntn-fila så den blir hetende nav.XXX.xml. Da overskriver den fila som har det navnet nå.
- insert'e en record i partner_cpa_archive for cpa-id yyyyMMdd_nav:XXX, med deleted=1, ellers uendrede verdier
- insert'e en record i partner_cpa_archive for cpa-id nav:XXX, med egen/ny partner_cpp_id og mottak_id
- endre record'en i partner_cpa som har cpa-id nav:XXX så den får verdiene fra den nye recorden over
- slette record'en i partner_cpa som har cpa-id yyyyMMdd_nav:XXX
Fra Parviz: partner_cpp_id og mottak_id fylles ut med f.eks. timestamp eller "cpa-bestilling-oppdatering"
Eksempel på CPP_ID fra admin: nav.K148586.20260217101115, MOTTAK_ID: 2602160959cppa50771
 */
    internal suspend fun activate(connector: NFSConnector, entry: ChannelSftp.LsEntry, cpaArchiveRepository: CpaArchiveRepository) {
        activateAtFilesystem(connector, entry)
        activateInDb(entry, cpaArchiveRepository)
    }

    internal suspend fun activateAtFilesystem(connector: NFSConnector, entry: ChannelSftp.LsEntry) {
        val activatedCpaFilename = getActivatedName(entry.filename)
        if (activatedCpaFilename == null) {
            log.warn("Failed to convert ${entry.filename} to activated CPA file name")
            return
        }
        try {
            connector.rename(entry.filename, activatedCpaFilename)
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

    internal fun getCpaPart(filename: String): String? {
        // Example: 01230800_nav.60120._R_Zm9ybnllbHNl._R_.qrntn  ->  01230800_nav.60120.xml
        if (filename.length < 9 || filename[8] != '_') {
            log.warn("$filename does not start with 8-character TS and underscore, cannot be converted to activated name")
            return null
        }
        val endIndex = filename.indexOf("._R_")
        if (endIndex == -1 || endIndex < 8) {
            log.warn("$filename does not contain proper CPA ID between first and second underscore, cannot be converted to activated name")
            return null
        }
        val cpaId = filename.substring(0, endIndex)
        if (cpaId.endsWith(".")) {
            return cpaId + "xml"
        }
        return cpaId + ".xml"
    }

    internal suspend fun activateInDb(entry: ChannelSftp.LsEntry, cpaArchiveRepository: CpaArchiveRepository) {
        val activatedCpaFilename = getActivatedName(entry.filename)
        val tmpFilename = getCpaPart(entry.filename)
        if (activatedCpaFilename == null || tmpFilename == null) {
            log.warn("Failed to convert ${entry.filename} to activated CPA file name")
            return
        }
        val cpaId = activatedCpaFilename.replace(".xml", "").replace(".", ":")
        val tmpCpaId = tmpFilename.replace(".xml", "").replace(".", ":")
        log.info("Activating CPA $cpaId from $tmpCpaId")
        val latestTmp = cpaArchiveRepository.findLatestByCpaId(tmpCpaId)
        if (latestTmp != null) {
            // lag ny record med tmpid, eneste endring er deleted = 1
            cpaArchiveRepository.insertCopy(latestTmp.id)
            val copyTmp = cpaArchiveRepository.findLatestByCpaId(tmpCpaId)
            if (copyTmp != null) cpaArchiveRepository.setDeleted(copyTmp.id)
            // lag ny record med ordentlig id, endringer: deleted/quarantined = 0, ny partner_cpp_id og mottak_id
            val timestampString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
            val cppAndMottaksId = "$timestampString.cpa-aktivering"
            log.info("Creating new CPA record for $cpaId with CPP_ID=$cppAndMottaksId")
            cpaArchiveRepository.insertCopy(latestTmp.id)
            val copy = cpaArchiveRepository.findLatestByCpaId(tmpCpaId)
            if (copy != null) {
                cpaArchiveRepository.setAsNewCpa(copy.id, cpaId, cppAndMottaksId)
                // endre gjeldende CPA-record med verdier fra nyeste archive
                cpaArchiveRepository.updateFromArchive(cpaId, copy.id)
                // slette midlertidig CPA
                cpaArchiveRepository.deleteTmpCpa(tmpCpaId)
            }
        }
    }
}
