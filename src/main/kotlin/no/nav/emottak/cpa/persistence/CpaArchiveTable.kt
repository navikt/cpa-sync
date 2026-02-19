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

    override val primaryKey = PrimaryKey(id)
}
/*
		String sql = DbHelper.getQueryWithStartAndMax(
				"select id, partner_id, cpa_id, partner_cpp_id, nav_cpp_id, quarantined, reason, " +
				"partner_subjectdn, partner_endpoint, created, deleted, created_by, mottak_id, mail_receiver," +
				"valid_from, valid_to from partner_cpa_archive "

			cpa.setId(rs.getInt("id"));
			cpa.setMsgId(rs.getString("mottak_id"));
			cpa.setMailReceiver(rs.getString("mail_receiver"));
			cpa.setCreated(rs.getTimestamp("created"));
			cpa.setDeleted(rs.getInt("deleted") == 1);
			cpa.setCreatedBy(rs.getString("created_by"));
			cpa.setValidFrom(rs.getTimestamp("valid_from"));
			cpa.setValidTo(rs.getTimestamp("valid_to"));
			cpa.setQuarantined(rs.getInt("quarantined") == 1);
			cpa.setReason(rs.getString("reason"));
 */
