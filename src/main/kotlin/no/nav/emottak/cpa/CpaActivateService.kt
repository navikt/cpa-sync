package no.nav.emottak.cpa

import com.jcraft.jsch.ChannelSftp
import no.nav.emottak.cpa.nfs.NFSConnector
import no.nav.emottak.cpa.persistence.CpaArchiveRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

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

    internal suspend fun activateInDb(entry: ChannelSftp.LsEntry, cpaArchiveRepository: CpaArchiveRepository) {
        val activatedCpaFilename = getActivatedName(entry.filename)
        if (activatedCpaFilename == null) {
            log.warn("Failed to convert ${entry.filename} to activated CPA file name")
            return
        }
        val cpaId = activatedCpaFilename.replace(".xml", "").replace(".", ":")
        // TODO trenger kanskje delete også ???
        cpaArchiveRepository.findLatestByCpaId(cpaId)?.let {
                cpa ->
            cpaArchiveRepository.setNotQuarantined(cpa.id)
        }
    }
}
