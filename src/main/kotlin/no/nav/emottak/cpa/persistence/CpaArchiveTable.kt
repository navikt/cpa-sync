package no.nav.emottak.cpa.persistence

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table

object CpaArchiveTable : Table("partner_cpa_archive") {
    val id: Column<Int> = integer("id")
    val cpaId: Column<String> = varchar("cpa_id", 256)
    val quarantined: Column<Boolean> = bool("quarantined")
    val deleted: Column<Boolean> = bool("deleted")
    val partnerCppId: Column<String> = varchar("partner_cpp_id", 256)
    val mottakId: Column<String> = varchar("mottak_id", 256)
    val reason: Column<String> = varchar("reason", 256)

    override val primaryKey = PrimaryKey(id)
}
