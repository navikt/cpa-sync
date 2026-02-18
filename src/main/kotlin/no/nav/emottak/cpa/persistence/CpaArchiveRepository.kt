package no.nav.emottak.cpa.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class ArchivedCpa(
    val id: Int,
    val cpaId: String,
    val quarantined: Boolean,
    val deleted: Boolean
)

class CpaArchiveRepository(private val database: Database) {

    suspend fun findLatestByCpaId(cpaId: String): ArchivedCpa? = withContext(Dispatchers.IO) {
        transaction(database) {
            CpaArchiveTable.select(CpaArchiveTable.columns)
                .where { CpaArchiveTable.cpaId eq cpaId }
                .orderBy(CpaArchiveTable.id to SortOrder.DESC)
                .limit(1, offset = 0)
                .mapNotNull {
                    ArchivedCpa(it[CpaArchiveTable.id], it[CpaArchiveTable.cpaId], it[CpaArchiveTable.quarantined], it[CpaArchiveTable.deleted])
                }
                .singleOrNull()
        }
    }
/* I gamle eMottak:
		criteria.setId(cpaid);
		criteria.setFirstResult(1);
		criteria.setMaxResults(1);
		criteria.setSortSql("order by id desc");

 */

    suspend fun setDeleted(id: Int) = withContext(Dispatchers.IO) {
        transaction(database) {
            CpaArchiveTable.update({
                CpaArchiveTable.id eq id
            }) {
                it[deleted] = true
            }
        }
    }

    suspend fun insertCopy(id: Int) = withContext(Dispatchers.IO) {
        transaction(database) {
            exec(insertCopySql + " $id")
        }
    }

    val insertCopySql = """
insert into partner_cpa_archive (
ID,
PARTNER_ID, CPA_ID, NAV_CPP_ID, PARTNER_CPP_ID, PARTNER_SUBJECTDN, PARTNER_ENDPOINT, CREATED, MOTTAK_ID,
MAIL_RECEIVER, VALID_FROM, VALID_TO, CPP, CPA, ISSUER_NONREP, SERIALNO_NONREP, VALIDTO_NONREP, ISSUER_DATAENC,
SERIALNO_DATAENC, VALIDTO_DATAENC, ISSUER_SSL, SERIALNO_SSL, VALIDTO_SSL, DELETED, CREATED_BY, QUARANTINED, REASON
) select
PARTNER_CPA_ARCHIVE_SEQ.NEXTVAL,
PARTNER_ID, CPA_ID, NAV_CPP_ID, PARTNER_CPP_ID, PARTNER_SUBJECTDN, PARTNER_ENDPOINT, CURRENT_TIMESTAMP, MOTTAK_ID,
MAIL_RECEIVER, VALID_FROM, VALID_TO, CPP, CPA, ISSUER_NONREP, SERIALNO_NONREP, VALIDTO_NONREP, ISSUER_DATAENC,
SERIALNO_DATAENC, VALIDTO_DATAENC, ISSUER_SSL, SERIALNO_SSL, VALIDTO_SSL, DELETED, 'cpa-activate', QUARANTINED, REASON
 from partner_cpa_archive where id = 
"""
    suspend fun setAsNewCpa(id: Int, useCpaId: String, cppAndMottaksId: String) = withContext(Dispatchers.IO) {
        transaction(database) {
            CpaArchiveTable.update({
                CpaArchiveTable.id eq id
            }) {
                it[cpaId] = useCpaId
                it[quarantined] = false
                it[deleted] = false
                it[partnerCppId] = cppAndMottaksId
                it[mottakId] = cppAndMottaksId
            }
        }
    }

    // todo funker hvis CPA finnes fra før. Må gjøre insert ellers
    suspend fun updateFromArchive(cpaId: String, id: Int) = withContext(Dispatchers.IO) {
        transaction(database) {
            exec(
                "update partner_cpa " +
                    "set PARTNER_ID = (select PARTNER_ID from partner_cpa_archive where id = $id ), " +
                    "NAV_CPP_ID = (select NAV_CPP_ID from partner_cpa_archive where id = $id) , " +
                    "PARTNER_CPP_ID = (select PARTNER_CPP_ID from partner_cpa_archive where id = $id ), " +
                    "PARTNER_SUBJECTDN = (select PARTNER_SUBJECTDN from partner_cpa_archive where id = $id ), " +
                    "PARTNER_ENDPOINT = (select PARTNER_ENDPOINT from partner_cpa_archive where id = $id ) " +
                    "where CPA_ID = '$cpaId' "
            )
        }
    }

    suspend fun deleteTmpCpa(cpaId: String) = withContext(Dispatchers.IO) {
        transaction(database) {
            exec("delete from partner_cpa where CPA_ID = '$cpaId'")
        }
    }

    // just for test
    suspend fun countQuarantined(): Long = withContext(Dispatchers.IO) {
        transaction(database) {
            CpaArchiveTable.select(CpaArchiveTable.id)
                .where(CpaArchiveTable.quarantined eq true)
                .count()
        }
    }
}
