package no.nav.emottak.cpa

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import no.nav.emottak.cpa.nfs.NFSConnector
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CpaSyncServiceTest {
    private val mockCpaRepoClient: HttpClient = mockk(relaxed = true)
    private val mockHttpResponse: HttpResponse = mockk(relaxed = true)
    private val mockNFSConnector: NFSConnector = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic("no.nav.emottak.cpa.HttpClientKt")
        clearMocks(mockCpaRepoClient)
        coEvery { mockCpaRepoClient.deleteCPAinCPARepo(any()) } returns mockHttpResponse
        coEvery { mockCpaRepoClient.putCPAinCPARepo(any(), any()) } returns mockHttpResponse
    }

    @Test
    fun `should return true for XML file`() {
        val lsEntry = mockLsEntry("nav.qass.12345.xml", "2024-01-01T00:00:00Z")
        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNFSConnector)

        assertTrue(cpaSyncService.isXmlFileEntry(lsEntry))
    }

    @Test
    fun `should return false for invalid file type`() {
        val lsEntry = mockLsEntry("nav.qass.12345.txt", "2024-01-01T00:00:00Z")
        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNFSConnector)

        assertFalse(cpaSyncService.isXmlFileEntry(lsEntry))
    }

    @Test
    fun `should not send request if invalid file type`() = runBlocking {
        val lsEntry = mockLsEntry("nav.qass.12345.txt", "2025-01-01T00:00:00Z")
        val mockNfs = mockNfsFromEntries(listOf(lsEntry), listOf("nav:qass:12345"))

        mockCpaRepoFromMap(emptyMap())

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        cpaSyncService.sync()

        coVerify(exactly = 0) { mockCpaRepoClient.putCPAinCPARepo(any(), any()) }
        coVerify(exactly = 0) { mockCpaRepoClient.deleteCPAinCPARepo(any()) }
    }

    @Test
    fun `should get cpaId from xml content`() = runBlocking {
        val lsEntry = mockLsEntry("nav.qass.12345.xml", "2025-01-01T00:00:00Z")
        val mockNfs = mockNfsFromEntries(listOf(lsEntry), listOf("nav:qass:12345"))

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        val nfsCpa = cpaSyncService.getNfsCpa(mockNfs, lsEntry)

        assertEquals("nav:qass:12345", nfsCpa?.id)
    }

    @Test
    fun `should find cpaId from an actual CPA file`() = runBlocking {
        val lsEntry = mockLsEntry("nav.qass.12345.txt", "2025-01-01T00:00:00Z")
        val mockNfs: NFSConnector = mockk {
            every { folder() } returns Vector<ChannelSftp.LsEntry>().apply { add(lsEntry) }
            every { file(any()) } returns File(ClassLoader.getSystemResource("cpa/nav.qass.12345.xml").file).inputStream()
            every { close() } just Runs
        }

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        val nfsCpa = cpaSyncService.getNfsCpa(mockNfs, lsEntry)

        assertEquals("nav:qass:12345", nfsCpa?.id)
    }

    @Test
    fun `should return null if cpa ID is not found`() = runBlocking {
        val lsEntry = mockLsEntry("nav.qass.missing.txt", "2025-01-01T00:00:00Z")
        val mockNfs = mockNfsFromEntries(listOf(lsEntry), listOf(""))

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        val cpa = cpaSyncService.getNfsCpa(mockNfs, lsEntry)

        assertTrue(cpa == null)
    }

    @Test
    fun `nfs mTime conversion to db timestamp should be accurate`() {
        val mTimeInSeconds = 1704067200L
        val expectedTimestamp = "2024-01-01T00:00:00Z"

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNFSConnector)

        val actualTimestamp = cpaSyncService.getLastModified(mTimeInSeconds)

        assert(expectedTimestamp == actualTimestamp)
    }

    @Test
    fun `zipping and unzipping should compress the file and return the same result`() {
        val cpaFile = File(ClassLoader.getSystemResource("cpa/nav.qass.12345.xml").file).readText()

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNFSConnector)

        val zipped = cpaSyncService.zipCpaContent(cpaFile)
        val unzipped = cpaSyncService.unzipCpaContent(zipped)

        assert(zipped.size < unzipped.toByteArray().size)
        assert(cpaFile == unzipped)
    }

    @Test
    fun `sync should abort if duplicate cpa IDs is found in nfs`() = runBlocking {
        val lsEntries = listOf(
            mockLsEntry("nav.qass.12345.xml", "2025-01-01T00:00:00Z"),
            mockLsEntry("nav.qass.12345.xml", "2025-01-01T00:00:00Z")
        )
        val mockNfs = mockNfsFromEntries(lsEntries, listOf("nav:qass:12345", "nav:qass:12345"))

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)

        val exception = assertThrows<IllegalArgumentException> {
            cpaSyncService.getNfsCpaMap()
        }

        assertTrue(exception.message!!.contains("NFS contains duplicate CPA IDs. Aborting sync."))
    }

    @Test
    fun `should skip processing if cpa ID is not found in CPA`() = runBlocking {
        val lsEntry = mockLsEntry("nav.qass.missing.txt", "2025-01-01T00:00:00Z")
        val mockNfs = mockNfsFromEntries(listOf(lsEntry), listOf(""))

        val dbCpa = emptyMap<String, String>()
        mockCpaRepoFromMap(dbCpa)

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        cpaSyncService.sync()

        coVerify(exactly = 0) { mockCpaRepoClient.putCPAinCPARepo(any(), any()) }
        coVerify(exactly = 0) { mockCpaRepoClient.deleteCPAinCPARepo(any()) }
    }

    @Test
    fun `sync should do nothing when all entries match database`() = runBlocking {
        val nfsCpa = mapOf("nav:qass:12345" to "2024-01-01T00:00:00Z")
        val dbCpa = mapOf("nav:qass:12345" to "2024-01-01T00:00:00Z")

        val mockNfs = mockNfsFromMap(nfsCpa)
        mockCpaRepoFromMap(dbCpa)

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        cpaSyncService.sync()

        coVerify(exactly = 0) { mockCpaRepoClient.putCPAinCPARepo(any(), any()) }
        coVerify(exactly = 0) { mockCpaRepoClient.deleteCPAinCPARepo(any()) }
    }

    @Test
    fun `sync should ignore entries when db timestamp is newer`() = runBlocking {
        val nfsCpa = mapOf("nav:qass:12345" to "2024-01-01T00:00:00Z")
        val dbCpa = mapOf("nav:qass:12345" to "2025-01-01T00:00:00Z")

        val mockNfs = mockNfsFromMap(nfsCpa)
        mockCpaRepoFromMap(dbCpa)

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        cpaSyncService.sync()

        coVerify(exactly = 0) { mockCpaRepoClient.putCPAinCPARepo(any(), any()) }
        coVerify(exactly = 0) { mockCpaRepoClient.deleteCPAinCPARepo(any()) }
    }

    @Test
    fun `sync should upsert when nfs timestamp is newer`() = runBlocking {
        val nfsCpa = mapOf("nav:qass:12345" to "2025-01-01T00:00:00Z")
        val dbCpa = mapOf("nav:qass:12345" to "2024-01-01T00:00:00Z")

        val mockNfs = mockNfsFromMap(nfsCpa)
        mockCpaRepoFromMap(dbCpa)

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        cpaSyncService.sync()

        coVerify(exactly = 1) { mockCpaRepoClient.putCPAinCPARepo(any(), any()) }
    }

    @Test
    fun `sync should upsert when entry does not exist in db`() = runBlocking {
        val nfsCpa = mapOf("nav:qass:12345" to "2024-01-01T00:00:00Z")
        val dbCpa = emptyMap<String, String>()

        val mockNfs = mockNfsFromMap(nfsCpa)
        mockCpaRepoFromMap(dbCpa)

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        cpaSyncService.sync()

        coVerify(exactly = 1) { mockCpaRepoClient.putCPAinCPARepo(any(), any()) }
    }

    @Test
    fun `sync should only delete entries that are stale`() = runBlocking {
        val nfsCpa = mapOf("nav:qass:12345" to "2024-01-01T00:00:00Z")
        val dbCpa = mapOf(
            "nav:qass:12345" to "2024-01-01T00:00:00Z",
            "nav:qass:67890" to "2024-01-01T00:00:00Z"
        )

        val mockNfs = mockNfsFromMap(nfsCpa)
        mockCpaRepoFromMap(dbCpa)

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        cpaSyncService.sync()

        coVerify(exactly = 1) { mockCpaRepoClient.deleteCPAinCPARepo("nav:qass:67890") }
    }

    @Test
    fun `sync should delete and upsert in same sync`() = runBlocking {
        val nfsCpa = mapOf("nav:qass:12345" to "2024-01-01T00:00:00Z")
        val dbCpa = mapOf("nav:qass:67890" to "2024-01-01T00:00:00Z")

        val mockNfs = mockNfsFromMap(nfsCpa)
        mockCpaRepoFromMap(dbCpa)

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        cpaSyncService.sync()

        coVerify(exactly = 1) { mockCpaRepoClient.putCPAinCPARepo(any(), any()) }
        coVerify(exactly = 1) { mockCpaRepoClient.deleteCPAinCPARepo("nav:qass:67890") }
    }

    @Test
    fun `sync should upsert correct CPA content`() = runBlocking {
        val nfsCpa = mapOf("nav:qass:12345" to "2024-01-01T00:00:00Z")
        val dbCpa = emptyMap<String, String>()

        val mockNfs = mockNfsFromMap(nfsCpa)
        mockCpaRepoFromMap(dbCpa)

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)
        cpaSyncService.sync()

        val expectedFileContent = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <cppa:CollaborationProtocolAgreement cppa:cpaid="nav:qass:12345">
                <!-- CPA content removed -->
            </cppa:CollaborationProtocolAgreement>
        """.trimIndent()

        coVerify(exactly = 1) {
            mockCpaRepoClient.putCPAinCPARepo(expectedFileContent, "2024-01-01T00:00:00Z")
        }
    }

    @Test
    fun `multiple syncs should persist CPA only once`() = runBlocking {
        val lsEntry = mockLsEntry("nav.qass.12345.xml", "2025-01-01T00:00:00Z")
        val mockNfs: NFSConnector = mockk {
            every { folder() } returns Vector<ChannelSftp.LsEntry>().apply { add(lsEntry) }
            every { file(any()) } answers { ByteArrayInputStream(simulateFileContent("nav:qass:12345").toByteArray()) }
            every { close() } just Runs
        }

        val dbCpaMapFirst = mapOf("nav:qass:12345" to "2024-01-01T00:00:00Z")
        val dbCpaMapSecond = mapOf("nav:qass:12345" to "2025-01-01T00:00:00Z")

        var callCount = 0
        coEvery { mockCpaRepoClient.getCPATimestamps() } answers {
            callCount++
            when (callCount) {
                1 -> dbCpaMapFirst
                else -> dbCpaMapSecond
            }
        }

        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNfs)

        cpaSyncService.sync()
        cpaSyncService.sync()
        cpaSyncService.sync()

        coVerify(exactly = 1) {
            mockCpaRepoClient.putCPAinCPARepo(any(), any())
        }
        coVerify(exactly = 0) {
            mockCpaRepoClient.deleteCPAinCPARepo(any())
        }
    }

    @Test
    fun `upsert check should return true if nfs timestamp is newer than db timestamp`() {
        val cpaSyncService = CpaSyncService(mockCpaRepoClient, mockNFSConnector)

        assertTrue { cpaSyncService.shouldUpsertCpa("2025-01-01T00:00:00Z", null) }
        assertTrue { cpaSyncService.shouldUpsertCpa("2025-01-01T00:00:00Z", "2024-01-01T00:00:00Z") }
        assertFalse { cpaSyncService.shouldUpsertCpa("2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z") }
        assertFalse { cpaSyncService.shouldUpsertCpa("2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z") }
    }

    @Test
    fun `sync should handle SftpException`() = runBlocking {
        val expectedSftpException = SftpException(4, "SFTP error")
        coEvery { mockCpaRepoClient.getCPATimestamps() } throws expectedSftpException
        val mockedNFSConnector = mockNfsFromMap(emptyMap())
        val cpaSyncService = spyk(CpaSyncService(mockCpaRepoClient, mockedNFSConnector))

        val resultException = assertFailsWith<SftpException> {
            cpaSyncService.sync()
        }

        assert(expectedSftpException == resultException)
        verify { cpaSyncService.logFailure(expectedSftpException) }
    }

    @Test
    fun `sync should handle a generic exception`() = runBlocking {
        val expectedException = Exception("generic error")
        coEvery { mockCpaRepoClient.getCPATimestamps() } throws expectedException

        val mockedNFSConnector = mockNfsFromMap(emptyMap())
        val cpaSyncService = spyk(CpaSyncService(mockCpaRepoClient, mockedNFSConnector))

        val resultException = assertFailsWith<Exception> {
            cpaSyncService.sync()
        }

        assert(expectedException == resultException)
        verify { cpaSyncService.logFailure(expectedException) }
    }

    @Test
    fun `isActivationDue works for various cases`() = runBlocking {
        val mockedNFSConnector = mockNfsFromMap(emptyMap())
        val cpaSyncService = spyk(CpaSyncService(mockCpaRepoClient, mockedNFSConnector))

        val justNow = nowInActivationTimezone().format(DateTimeFormatter.ofPattern("MMddHHmm"))
        val fiveMinutesAgo = nowInActivationTimezone().minusMinutes(5).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        val inFiveMinutes = nowInActivationTimezone().plusMinutes(5).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        assertEquals(true, cpaSyncService.isActivationDue(justNow + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Activation just now")
        assertEquals(true, cpaSyncService.isActivationDue(fiveMinutesAgo + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Activation five minutes ago")
        assertEquals(false, cpaSyncService.isActivationDue(inFiveMinutes + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Activation in five minutes")

        // Service only processes today's activations
        val yesterday = nowInActivationTimezone().minusDays(1).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        assertEquals(false, cpaSyncService.isActivationDue(yesterday + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Activation one month ago")

        val tomorrow = nowInActivationTimezone().plusDays(1).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        assertEquals(false, cpaSyncService.isActivationDue(tomorrow + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Activation in one month")

        assertEquals(false, cpaSyncService.isActivationDue("0127080_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Less than 8 chars TS")
        assertEquals(false, cpaSyncService.isActivationDue("012708000_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "More than 8 chars TS")
        assertEquals(false, cpaSyncService.isActivationDue("01270800-nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Not underscore at pos 9")
        assertEquals(false, cpaSyncService.isActivationDue("01270800"), "Too short filename")
        assertEquals(false, cpaSyncService.isActivationDue("0127080X_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Rubbish TS 1")
        assertEquals(false, cpaSyncService.isActivationDue("X1270800_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Rubbish TS 2")
    }

    internal fun nowInActivationTimezone(): LocalDateTime {
        return LocalDateTime.now(ACTIVATION_TIMEZONE)
    }
    @Test
    fun `getActivatedName works for various cases`() = runBlocking {
        val mockedNFSConnector = mockNfsFromMap(emptyMap())
        val cpaSyncService = spyk(CpaSyncService(mockCpaRepoClient, mockedNFSConnector))

        assertEquals("nav.60120.xml", cpaSyncService.getActivatedName("01230800_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Example case")
        assertEquals("nav.60120.xml", cpaSyncService.getActivatedName("01230800_nav.60120_R_Zm9ybnllbHNl._R_.qrntn"), "Example case without dot")
        assertEquals("nav.60120.xml", cpaSyncService.getActivatedName("01230800_nav.60120_"), "Example case minimal")
        assertEquals("nav.qass.60120.xml", cpaSyncService.getActivatedName("01230800_nav.qass.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Preprod case")
        assertEquals(null, cpaSyncService.getActivatedName("01230800_nav.60120.Zm9ybnllbHNl.qrntn"), "Missing second underscore")
        assertEquals(null, cpaSyncService.getActivatedName("01230800_n60120._R_Zm9ybnllbHNl._R_.qrntn"), "Too short invalid ID string")
        assertEquals(null, cpaSyncService.getActivatedName("01230800nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "Missing first underscore")
        assertEquals(null, cpaSyncService.getActivatedName("01230800nav.60120.Zm9ybnllbHNl.qrntn"), "No underscores")
        assertEquals(null, cpaSyncService.getActivatedName("012308000_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "First underscore in pos 10 instead of 9")
        assertEquals(null, cpaSyncService.getActivatedName("0123080_nav.60120._R_Zm9ybnllbHNl._R_.qrntn"), "First underscore in pos 8 instead of 9")
    }

    @Test
    fun `activatePendingCpas works for various file names`() = runBlocking {
        val mockedNFSConnector = mockNfsFromMap(emptyMap())
        val cpaSyncService = spyk(CpaSyncService(mockCpaRepoClient, mockedNFSConnector))

        val oneMinuteAgo = nowInActivationTimezone().minusMinutes(1).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        // Note: since the service will only process today's files, activation at 1 hour ago will be ignored if you run this right after midnight
        val oneHourAgo = nowInActivationTimezone().minusHours(1).format(DateTimeFormatter.ofPattern("MMddHHmm"))
        val tomorrow = nowInActivationTimezone().plusDays(1).format(DateTimeFormatter.ofPattern("MMddHHmm"))

        val entryToBeActivated1 = mockLsEntry(oneMinuteAgo + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryToBeActivated2 = mockLsEntry(oneHourAgo + "_nav.60121_R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryNotToBeActivatedYet = mockLsEntry(tomorrow + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryWithoutProperTs = mockLsEntry("nav.60120._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryWithMissingDigitInTs = mockLsEntry("0123080_nav.60120._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryWithRubbishID = mockLsEntry("01230800_nav60._R_Zm9ybnllbHNl._R_.qrntn", "2025-01-01T00:00:00Z")
        val entryWithoutProperSuffix = mockLsEntry("01230800_nav.60120._R_Zm9ybnllbHNl._R_", "2025-01-01T00:00:00Z")
        coEvery {
            mockedNFSConnector.folder()
        }.returns(Vector<ChannelSftp.LsEntry>(listOf(entryToBeActivated1, entryToBeActivated2, entryNotToBeActivatedYet, entryWithoutProperTs, entryWithMissingDigitInTs, entryWithRubbishID, entryWithoutProperSuffix)))

        cpaSyncService.activatePendingCpas()
        verify(exactly = 2) {
            runBlocking { mockedNFSConnector.copy(any(), any()) }
        }
        verify {
            runBlocking {
                mockedNFSConnector.copy(oneMinuteAgo + "_nav.60120._R_Zm9ybnllbHNl._R_.qrntn", "nav.60120.xml")
                mockedNFSConnector.copy(oneHourAgo + "_nav.60121_R_Zm9ybnllbHNl._R_.qrntn", "nav.60121.xml")
            }
        }
    }

    private fun mockCpaRepoFromMap(dbCpaMap: Map<String, String>) {
        coEvery { mockCpaRepoClient.getCPATimestamps() } returns dbCpaMap
    }

    private fun mockNfsFromMap(nfsCpaMap: Map<String, String>): NFSConnector {
        val lsEntries = nfsCpaMap.map { mockLsEntry(createFilename(it.key), it.value) }

        return mockNfsFromEntries(lsEntries, nfsCpaMap.keys.toList())
    }

    private fun mockNfsFromEntries(lsEntries: List<ChannelSftp.LsEntry>, nfsCpaIds: List<String>): NFSConnector {
        return mockk {
            every { folder() } returns Vector<ChannelSftp.LsEntry>().apply { addAll(lsEntries) }
            every { file(any()) } returnsMany nfsCpaIds.map { ByteArrayInputStream(simulateFileContent(it).toByteArray()) }
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
