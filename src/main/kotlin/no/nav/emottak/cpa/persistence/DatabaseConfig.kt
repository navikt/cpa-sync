package no.nav.emottak.cpa.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import no.nav.emottak.utils.environment.getEnvVar
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

fun configureCpaArchiveRepository(databaseConfig: DatabaseConfig): CpaArchiveRepository? {
    try {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = databaseConfig.jdbcUrl
            driverClassName = "oracle.jdbc.OracleDriver"
            maximumPoolSize = databaseConfig.maxPoolSize
            username = readFromFile(databaseConfig.secretPath + "/username")
            log.debug("DB user: " + username)
            if (username.isBlank()) {
                username = getEnvVar("CPA_DB_USERNAME", "")
                log.debug("DB user 2: " + username)
            }
            password = readFromFile(databaseConfig.secretPath + "/password")
            if (password.isBlank()) {
                password = getEnvVar("CPA_DB_PASSWORD", "")
                log.debug("PW read from env")
            }
        }
        log.info("DB URL set to {}, with user {}", hikariConfig.jdbcUrl, hikariConfig.username)
        val dataSource = HikariDataSource(hikariConfig)
        val database = Database.connect(dataSource)
        return CpaArchiveRepository(database)
    } catch (e: Exception) {
        log.error("Failed to connect to database", e)
        return null
    }
}

fun readFromFile(path: String): String {
    if (!Files.exists(Paths.get(path))) {
        log.error("Vault/secret file $path not found")
        return ""
    }
    return Files.readString(Paths.get(path))
}
