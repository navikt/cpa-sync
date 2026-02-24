package no.nav.emottak.cpa.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.datetime.TimeZone
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

private val log = LoggerFactory.getLogger("no.nav.emottak.cpa.persistence.DatabaseConfig")

data class DatabaseConfig(
    val jdbcUrl: String,
    val secretPath: String,
    val maxPoolSize: Int = 4
)

fun configureCpaArchiveRepository(databaseConfig: DatabaseConfig): CpaArchiveRepository {
    // Når vi bruker CURRENT TIMESTAMP i inserts, får vi GMT-tid. Prøver å fikse det med denne.
    val ourTimezone = TimeZone.currentSystemDefault().id
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = databaseConfig.jdbcUrl
        driverClassName = "oracle.jdbc.OracleDriver"
        maximumPoolSize = databaseConfig.maxPoolSize
        username = readFromFile(databaseConfig.secretPath + "/username")
        password = readFromFile(databaseConfig.secretPath + "/password")
        connectionInitSql = "ALTER SESSION SET TIME_ZONE = '$ourTimezone'"
    }
    log.info("DB URL set to {}, with user {}, init-sql: {}", hikariConfig.jdbcUrl, hikariConfig.username, hikariConfig.connectionInitSql)
    val dataSource = HikariDataSource(hikariConfig)
    val database = Database.connect(dataSource)

    return CpaArchiveRepository(database)
}

fun readFromFile(path: String): String {
    if (!Files.exists(Paths.get(path))) {
        log.error("Vault/secret file $path not found")
        return ""
    }
    return Files.readString(Paths.get(path))
}
