package no.nav.emottak.cpa

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import no.nav.emottak.cpa.nfs.NFSConnector
import no.nav.emottak.cpa.persistence.ArchivedCpa
import no.nav.emottak.cpa.persistence.CpaArchiveRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.test.assertEquals

class CpaActivateServiceTest {
    private val mockCpaArchiveRepository: CpaArchiveRepository = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic("no.nav.emottak.cpa.HttpClientKt")
    }

    @Test
    fun `isActivationDue works for various cases`() = runBlocking {
        val mockedNFSConnector = mockNfsFromMap(emptyMap())
        val cpaActivateService = spyk(CpaActivateService(mockedNFSConnector, mockCpaArchiveRepository))

        val justNow = nowInActivationTimezone().format(DateTimeFormatter.ofPattern("MMddHHmm"))
        val fiveMinutesAgo = nowInActivationTimezone().minusMinutes(5).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        val inFiveMinutes = nowInActivationTimezone().plusMinutes(5).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        assertEquals(true, cpaActivateService.isActivationDue(justNow + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Activation just now")
        assertEquals(true, cpaActivateService.isActivationDue(fiveMinutesAgo + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Activation five minutes ago")
        assertEquals(false, cpaActivateService.isActivationDue(inFiveMinutes + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Activation in five minutes")

        // Service only processes today's activations
        val yesterday = nowInActivationTimezone().minusDays(1).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        assertEquals(false, cpaActivateService.isActivationDue(yesterday + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Activation one month ago")

        val tomorrow = nowInActivationTimezone().plusDays(1).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        assertEquals(false, cpaActivateService.isActivationDue(tomorrow + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Activation in one month")

        assertEquals(false, cpaActivateService.isActivationDue("0127080_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Less than 8 chars TS")
        assertEquals(false, cpaActivateService.isActivationDue("012708000_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "More than 8 chars TS")
        assertEquals(false, cpaActivateService.isActivationDue("01270800-nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Not underscore at pos 9")
        assertEquals(false, cpaActivateService.isActivationDue("01270800"), "Too short filename")
        assertEquals(false, cpaActivateService.isActivationDue("0127080X_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Rubbish TS 1")
        assertEquals(false, cpaActivateService.isActivationDue("X1270800_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Rubbish TS 2")
    }

    internal fun nowInActivationTimezone(): LocalDateTime {
        return LocalDateTime.now(ACTIVATION_TIMEZONE)
    }

    @Test
    fun `getActivatedName works for various cases`() = runBlocking {
        val mockedNFSConnector = mockNfsFromMap(emptyMap())
        val cpaActivateService = spyk(CpaActivateService(mockedNFSConnector, mockCpaArchiveRepository))

        assertEquals("nav.60120.xml", cpaActivateService.getActivatedName("01230800_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Example case")
        assertEquals("nav.60120.xml", cpaActivateService.getActivatedName("01230800_nav.60120_R_Zm9ybnllbHNl._R_.qrntn"), "Example case without dot")
        assertEquals("nav.60120.xml", cpaActivateService.getActivatedName("01230800_nav.60120_"), "Example case minimal")
        assertEquals("nav.qass.60120.xml", cpaActivateService.getActivatedName("01230800_nav.qass.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Preprod case")
        assertEquals(null, cpaActivateService.getActivatedName("01230800_nav.60120.Zm9ybnllbHNl.qrntn"), "Missing second underscore")
        assertEquals(null, cpaActivateService.getActivatedName("01230800_n60120._R_Zm9ybnllbHNl._R_.qrntn"), "Too short invalid ID string")
        assertEquals(null, cpaActivateService.getActivatedName("01230800nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Missing first underscore")
        assertEquals(null, cpaActivateService.getActivatedName("01230800nav.60120.Zm9ybnllbHNl.qrntn"), "No underscores")
        assertEquals(null, cpaActivateService.getActivatedName("012308000_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "First underscore in pos 10 instead of 9")
        assertEquals(null, cpaActivateService.getActivatedName("0123080_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "First underscore in pos 8 instead of 9")
    }

    @Test
    fun `getCpaPart works for various cases`() = runBlocking {
        val mockedNFSConnector = mockNfsFromMap(emptyMap())
        val cpaActivateService = spyk(CpaActivateService(mockedNFSConnector, mockCpaArchiveRepository))

        assertEquals("01230800_nav.60120.xml", cpaActivateService.getCpaPart("01230800_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Example case")
        assertEquals("01230800_nav.qass.60120.xml", cpaActivateService.getCpaPart("01230800_nav.qass.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Preprod case")
    }

    @Test
    fun `activatePendingCpas works for various file names`() = runBlocking {
        val mockedNFSConnector = mockNfsFromMap(emptyMap())
        val cpaActivateService = spyk(CpaActivateService(mockedNFSConnector, mockCpaArchiveRepository))

        val oneMinuteAgo = nowInActivationTimezone().minusMinutes(1).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        // Note: since the service will only process today's files, activation at 1 hour ago will be ignored if you run this right after midnight
        val oneHourAgo = nowInActivationTimezone().minusHours(1).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        val tomorrow = nowInActivationTimezone().plusDays(1).format(DateTimeFormatter.ofPattern("MMddHHmm"))

        val entryToBeActivated1 = mockLsEntry(oneMinuteAgo + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryToBeActivated2 = mockLsEntry(oneHourAgo + "_nav.60121._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryNotToBeActivatedYet = mockLsEntry(tomorrow + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryWithoutProperTs = mockLsEntry("nav.60120._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryWithMissingDigitInTs = mockLsEntry("0123080_nav.60120._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryWithRubbishID = mockLsEntry("01230800_nav60._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryWithoutProperSuffix = mockLsEntry("01230800_nav.60120._R_Zm9ybnllbHNl._R_", "2025-01-01T00:00:00Z")
        coEvery {
            mockedNFSConnector.folder()
        }.returns(Vector<ChannelSftp.LsEntry>(listOf(entryToBeActivated1, entryToBeActivated2, entryNotToBeActivatedYet, entryWithoutProperTs, entryWithMissingDigitInTs, entryWithRubbishID, entryWithoutProperSuffix)))
        coEvery {
            mockCpaArchiveRepository.findLatestByCpaId(oneMinuteAgo + "_nav:60120")
        }.returns(ArchivedCpa(123, oneMinuteAgo + "_nav:60120", true, false))
            .andThen(ArchivedCpa(223, oneMinuteAgo + "_nav:60120", true, false))
            .andThen(ArchivedCpa(323, oneMinuteAgo + "_nav:60120", true, false))

        cpaActivateService.activatePendingCpas()
        verify {
            runBlocking {
                mockedNFSConnector.rename(oneMinuteAgo + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn", "nav.60120.xml")
                mockedNFSConnector.rename(oneHourAgo + "_nav.60121._R_Zm9ybnllbHNl._R_.qrntn", "nav.60121.xml")
            }
        }
        verify {
            runBlocking {
                mockCpaArchiveRepository.findLatestByCpaId(oneMinuteAgo + "_nav:60120")
                mockCpaArchiveRepository.insertCopy(123)
                mockCpaArchiveRepository.setDeleted(223)
                mockCpaArchiveRepository.setAsNewCpa(323, "nav:60120", any())
                mockCpaArchiveRepository.updateFromArchive("nav:60120", 323)
                mockCpaArchiveRepository.deleteTmpCpa(oneMinuteAgo + "_nav:60120")
            }
        }
    }

    private fun mockNfsFromMap(nfsCpaMap: Map<String, String>): NFSConnector {
        val lsEntries = nfsCpaMap.map { mockLsEntry(createFilename(it.key), it.value) }

        return mockNfsFromEntries(lsEntries, nfsCpaMap.keys.toList())
    }

    private fun mockNfsFromEntries(lsEntries: List<ChannelSftp.LsEntry>, nfsCpaIds: List<String>): NFSConnector {
        return mockk {
            every { folder() } returns Vector<ChannelSftp.LsEntry>().apply { addAll(lsEntries) }
            every { file(any()) } returnsMany nfsCpaIds.map { ByteArrayInputStream(simulateFileContent(it).toByteArray()) }
            every { rename(any(), any()) } just Runs
            every { close() } just Runs
        }
    }

    private fun mockLsEntry(entryName: String, timestamp: String): ChannelSftp.LsEntry =
        mockk {
            every { filename } returns entryName
            every { attrs } returns mockSftpAttrs(timestamp)
        }

    private fun mockSftpAttrs(timestamp: String): SftpATTRS = mockk {
        every { mTime } returns Instant.parse(timestamp).epochSecond.toInt()
    }

    private fun createFilename(cpaId: String): String {
        return "${cpaId.replace(":", ".")}.xml"
    }

    private fun simulateFileContent(cpaId: String): String {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <cppa:CollaborationProtocolAgreement cppa:cpaid="$cpaId">
                <!-- CPA content removed -->
            </cppa:CollaborationProtocolAgreement>
        """.trimIndent()
    }
}
