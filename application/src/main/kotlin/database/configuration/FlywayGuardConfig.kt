package database.configuration

import common.logging.DiscordLogger
import org.flywaydb.core.api.MigrationInfo
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
 * Also fails start-up when Flyway reports any migration as FAILED or still
 * PENDING after `migrate()`. Without this, a partially-applied schema will
 * let the bot come up but any feature that relies on the missing column or
 * table will 500 on first use — exactly the symptom we saw post-#267.
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
        if (required) {
            ensureAllMigrationsApplied(flyway.info().all().toList())
        }
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

        internal fun ensureAllMigrationsApplied(all: List<MigrationInfo>) {
            val failed = all.filter { it.state?.isFailed == true }
            val pending = all.filter { it.state?.isApplied == false && it.state?.isFailed == false }
            if (failed.isEmpty() && pending.isEmpty()) return

            val details = buildString {
                if (failed.isNotEmpty()) {
                    append("FAILED: ")
                    append(failed.joinToString(", ") { "${it.version}/${it.description}" })
                    if (pending.isNotEmpty()) append("; ")
                }
                if (pending.isNotEmpty()) {
                    append("PENDING: ")
                    append(pending.joinToString(", ") { "${it.version}/${it.description}" })
                }
            }
            throw IllegalStateException(
                "Flyway migrations are not fully applied ($details). The schema is out of " +
                    "sync with the deployed artifact — features that rely on the missing " +
                    "columns or tables will return 500. Fix the broken migration state and " +
                    "redeploy, or set toby.flyway.require-migrations=false to override."
            )
        }
    }
}
