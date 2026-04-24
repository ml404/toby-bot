package database.configuration

import common.logging.DiscordLogger
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Fails application start-up when Flyway can't see any migrations on the
 * classpath — e.g. because the nested database jar got repackaged without
 * its db.migration SQL resources. Without this guard Spring Boot would
 * happily start serving, silently skip every migration, and the first
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
        @Value($$"${toby.flyway.require-migrations:true}") required: Boolean
    ): FlywayMigrationStrategy = FlywayMigrationStrategy { flyway ->
        val discovered = flyway.info().all().size
        ensureMigrationsAvailable(required, discovered)
        logger.info("Flyway: $discovered migration(s) on classpath (required=$required)")
        flyway.migrate()
    }

    companion object {
        internal fun ensureMigrationsAvailable(required: Boolean, discovered: Int) {
            if (required && discovered == 0) {
                throw IllegalStateException(
                    "Flyway found 0 migrations on the classpath (classpath:db/migration). " +
                        "The deployed artifact is missing its schema migrations — refusing to " +
                        "start. Rebuild and redeploy, or set toby.flyway.require-migrations=false " +
                        "to override."
                )
            }
        }
    }
}
