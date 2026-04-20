import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import common.logging.DiscordLogger

/**
 * Fails application start-up when Flyway can't see any migrations on the
 * classpath — e.g. because `BOOT-INF/lib/database-*.jar` got repackaged
 * without its `db/migration/*.sql` resources. Without this guard Spring Boot
 * would happily start serving, silently skip every migration, and the first
 * request that touches a missing table returns 500.
 *
 * Can be disabled for specialised builds (ad-hoc test rigs, DB dumps) via the
 * `toby.flyway.require-migrations` property — default `true`.
 */
@Configuration
class FlywayGuardConfig {

    private val logger = DiscordLogger(FlywayGuardConfig::class.java)

    @Bean
    fun flywayMigrationStrategy(
        @Value("\${toby.flyway.require-migrations:true}") required: Boolean
    ): FlywayMigrationStrategy = FlywayMigrationStrategy { flyway ->
        val discovered = flyway.info().all().size
        if (required && discovered == 0) {
            throw IllegalStateException(
                "Flyway found 0 migrations on the classpath (classpath:db/migration). " +
                    "The deployed artifact is missing its schema migrations — refusing to " +
                    "start. Rebuild and redeploy, or set toby.flyway.require-migrations=false " +
                    "to override."
            )
        }
        logger.info("Flyway: $discovered migration(s) on classpath (required=$required)")
        flyway.migrate()
    }
}
