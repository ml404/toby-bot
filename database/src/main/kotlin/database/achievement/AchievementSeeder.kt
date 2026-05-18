package database.achievement

import database.dto.AchievementDto
import database.persistence.AchievementPersistence
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Idempotent startup seed of the achievement catalogue. Runs on
 * [ApplicationReadyEvent] so the schema (V36 migration) is guaranteed to
 * exist. Inserts any missing rows by `code`; existing rows are updated
 * in place so display tweaks (name, description, icon, reward values)
 * roll out on the next deploy without a migration.
 *
 * The `threshold` column is the unlock count — updating it after rows
 * already exist effectively re-tunes the difficulty for new users.
 * Users who already unlocked the achievement are unaffected.
 */
@Component
class AchievementSeeder(
    private val persistence: AchievementPersistence,
    // Constructor-injected so tests can pass a tailored list instead of
    // depending on the live AchievementCatalog. Defaults to the live
    // catalog so Spring's autowire path stays a no-arg-from-config call.
    private val specs: List<AchievementSpec> = AchievementCatalog.all
) {

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun seed() {
        specs.forEach { spec ->
            val existing = persistence.getByCode(spec.code)
            if (existing == null) {
                persistence.save(
                    AchievementDto(
                        code = spec.code,
                        name = spec.name,
                        description = spec.description,
                        category = spec.category,
                        icon = spec.icon,
                        xpReward = spec.xpReward,
                        creditReward = spec.creditReward,
                        threshold = spec.threshold,
                        hidden = spec.hidden,
                        createdAt = Instant.now()
                    )
                )
            } else {
                existing.name = spec.name
                existing.description = spec.description
                existing.category = spec.category
                existing.icon = spec.icon
                existing.xpReward = spec.xpReward
                existing.creditReward = spec.creditReward
                existing.threshold = spec.threshold
                existing.hidden = spec.hidden
                persistence.save(existing)
            }
        }
    }
}
