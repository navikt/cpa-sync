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
    val quarantined: Boolean
)

class CpaArchiveRepository(private val database: Database) {

    suspend fun findLatestByCpaId(cpaId: String): ArchivedCpa? = withContext(Dispatchers.IO) {
        transaction(database) {
            CpaArchiveTable.select(CpaArchiveTable.columns)
                .where { CpaArchiveTable.cpaId eq cpaId }
                .orderBy(CpaArchiveTable.id to SortOrder.DESC)
                .limit(1, offset = 0)
                .mapNotNull {
                    ArchivedCpa(it[CpaArchiveTable.id], it[CpaArchiveTable.cpaId], it[CpaArchiveTable.quarantined])
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

    suspend fun setNotQuarantined(id: Int) = withContext(Dispatchers.IO) {
        transaction(database) {
            CpaArchiveTable.update({
                CpaArchiveTable.id eq id
            }) {
                it[quarantined] = false
            }
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
