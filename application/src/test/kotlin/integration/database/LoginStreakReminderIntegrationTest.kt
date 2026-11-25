package integration.database

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import database.dto.social.LoginStreakDto
import database.persistence.social.LoginStreakPersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * Runs the reminder JPQL against real Postgres. The reminder must only pick
 * up streaks that are still alive (last claimed yesterday): a lapsed row
 * keeps its stale `current_streak` until the next claim resets it to 1, so
 * matching on `current_streak > 0` alone would nudge users about a streak
 * that is already gone.
 */
@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class,
        TestAppConfig::class,
        TestBotConfig::class,
    ]
)
@ActiveProfiles("test")
@Transactional
class LoginStreakReminderIntegrationTest {

    @Autowired
    lateinit var persistence: LoginStreakPersistence

    private val guildId = 7_270_001L
    private val otherGuildId = 7_270_002L
    private val today: LocalDate = LocalDate.of(2026, 11, 25)

    private fun seed(discordId: Long, lastClaim: LocalDate?, streak: Int = 5, guild: Long = guildId) {
        persistence.upsert(
            LoginStreakDto(
                discordId = discordId,
                guildId = guild,
                currentStreak = streak,
                longestStreak = streak,
                lastClaimDate = lastClaim,
                totalClaims = streak.toLong(),
            )
        )
    }

    @Test
    fun `only streaks last claimed yesterday are due for a reminder`() {
        seed(discordId = 1L, lastClaim = today.minusDays(1))          // at risk
        seed(discordId = 2L, lastClaim = today)                       // already claimed
        seed(discordId = 3L, lastClaim = today.minusDays(2))          // lapsed
        seed(discordId = 4L, lastClaim = today.minusDays(40))         // long lapsed
        seed(discordId = 5L, lastClaim = null, streak = 0)            // never claimed
        seed(discordId = 6L, lastClaim = today.minusDays(1), guild = otherGuildId)

        val due = persistence.findActiveStreaksDueForReminder(guildId, today)

        assertEquals(listOf(1L), due.map { it.discordId })
    }
}
