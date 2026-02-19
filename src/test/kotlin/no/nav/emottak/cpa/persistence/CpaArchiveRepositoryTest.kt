package no.nav.emottak.cpa.persistence

import com.jcraft.jsch.ChannelSftp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import no.nav.emottak.cpa.CpaActivateService
import no.nav.emottak.cpa.nfs.NFSConnector
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.jvm.javaClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.use

var repo: CpaArchiveRepository? = null

class CpaArchiveRepositoryTest {

    @BeforeEach
    fun setUp() {
        val db = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;MODE=Oracle;", driver = "org.h2.Driver")
        runSqlScript("/cpa_tables_ddl.sql")
        repo = CpaArchiveRepository(db)
    }

    @AfterEach
    fun clearData() {
        runSql("delete from partner_cpa_archive")
        runSql("delete from partner_cpa")
    }

    @Test
    fun testFindLatestByCpaId() = runBlocking {
        runSqlScript("/cpa_archive_data.sql")
        val onlyOne = repo?.findLatestByCpaId("onlyOne")
        assertEquals(1, onlyOne?.id, "Should find only one entry")
        val twoRecords = repo?.findLatestByCpaId("twoRecords")
        assertEquals(3, twoRecords?.id, "Should find the newest entry")
        val notExist = repo?.findLatestByCpaId("notExcist")
        assertTrue(notExist == null, "Should not find anything for non-existing ID")
    }

    @Test
    fun testSetDeleted() = runBlocking {
        runSqlScript("/cpa_archive_data.sql")
        repo?.setDeleted(1)
        val onlyOne = repo?.findLatestByCpaId("onlyOne")
        assertEquals(true, onlyOne?.deleted, "Should be deleted")
    }

    @Test
    fun testCountQuarantined() = runBlocking {
        runSqlScript("/cpa_archive_data.sql")
        var q = repo?.countQuarantined()
        assertEquals(0, q, "Quarantined")
        runSql("update partner_cpa_archive set QUARANTINED = true where ID = 1")
        q = repo?.countQuarantined()
        assertEquals(1, q, "Quarantined")
    }

    @Test
    fun testInsertCopy() = runBlocking {
        runSqlScript("/cpa_archive_data.sql")
        repo?.insertCopy(1)
        val onlyOne = repo?.findLatestByCpaId("onlyOne")
        assertEquals(101, onlyOne?.id, "New record with new ID")
        assertEquals("onlyOne", onlyOne?.cpaId, "New record for CPA")
    }

    @Test
    fun testUpdateFromArchive() = runBlocking {
        runSqlScript("/cpa_archive_data.sql")
        repo?.updateFromArchive("twoRecords", 3)
        var updatedPartnerId: String? = null
        transaction {
            exec("select * from partner_cpa where cpa_id = 'twoRecords'") { rs ->
                rs.next()
                updatedPartnerId = rs.getString("PARTNER_ID")
            }
        }
        assertEquals("21157", updatedPartnerId, "Updated partner ID")
    }

    @Test
    fun testDeleteTmpCpa() = runBlocking {
        runSqlScript("/cpa_archive_data.sql")
        repo?.deleteTmpCpa("twoRecords")
        var count: Int? = null
        transaction {
            exec("select count(*) from partner_cpa where cpa_id = 'twoRecords'") { rs ->
                rs.next()
                count = rs.getInt(1)
            }
        }
        assertEquals(0, count, "Deleted CPA")
    }

    @Test
    fun testCompleteActivationSequence() = runBlocking {
        runSqlScript("/cpa_activatecase_data.sql")
        val nfsMock: NFSConnector = mockk()
        val cpaActivateService = CpaActivateService(nfsMock, repo!!)
        val fileEntry: ChannelSftp.LsEntry = mockk { every { filename } returns "02101500_nav.12345._R_Zm9ybnllbHNl._R_.qrntn" }
        cpaActivateService.activateInDb(fileEntry, repo!!)
        var count: Int? = null
        transaction {
            exec("select count(*) from partner_cpa") { rs ->
                rs.next()
                count = rs.getInt(1)
                assertEquals(1, count, "CPAs in total")
            }
            exec("select count(*) from partner_cpa where cpa_id = 'nav:12345'") { rs ->
                rs.next()
                count = rs.getInt(1)
                assertEquals(1, count, "CPA with proper ID")
            }
            exec("select count(*) from partner_cpa_archive") { rs ->
                rs.next()
                count = rs.getInt(1)
                assertEquals(4, count, "Archived CPAs in total")
            }
            exec("select count(*) from partner_cpa_archive where cpa_id = 'nav:12345'") { rs ->
                rs.next()
                count = rs.getInt(1)
                assertEquals(1, count, "Archived CPAs with proper ID")
            }
            exec("select count(*) from partner_cpa_archive where cpa_id = '02101500_nav:12345'") { rs ->
                rs.next()
                count = rs.getInt(1)
                assertEquals(3, count, "Archived CPAs with tmp ID")
            }
        }
        return@runBlocking
//        assertEquals(101, onlyOne?.id, "New record with new ID")
//        assertEquals("onlyOne", onlyOne?.cpaId, "New record for CPA")
    }

    fun runSqlScript(path: String) {
        javaClass
            .getResourceAsStream(path)
            .bufferedReader()
            .use { reader ->
                for (sql in reader.readText().split(';')) {
                    if (sql.any { it.isLetterOrDigit() }) {
                        runSql(sql)
                    }
                }
            }
    }

    fun runSql(sql: String) {
        transaction {
            exec(sql)
        }
    }
}
