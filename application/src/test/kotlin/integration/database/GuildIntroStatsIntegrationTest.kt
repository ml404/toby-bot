package integration.database

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import common.intro.IntroHealth
import database.configuration.TestDatabaseConfig
import database.dto.music.MusicDto
import database.dto.user.UserDto
import database.persistence.music.MusicFilePersistence
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/**
 * Covers the one piece of this feature that only a real Postgres can answer
 * for: `getGuildIntroStats` is native SQL (`octet_length`, `COUNT(*) FILTER`)
 * because the whole point is to total the size of the stored audio without
 * loading it. A unit test with a mocked EntityManager would assert the string
 * we wrote, not that the database accepts it.
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
class GuildIntroStatsIntegrationTest {

    @Autowired
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var persistence: MusicFilePersistence

    private val guildId = 5150L
    private val otherGuildId = 6160L

    private fun persistUser(discordId: Long, guildId: Long): UserDto =
        UserDto(discordId = discordId, guildId = guildId).also {
            entityManager.persist(it)
            entityManager.flush()
        }

    private fun persistIntro(
        user: UserDto,
        index: Int,
        blob: ByteArray,
        enabled: Boolean = true,
        failures: Int = 0,
        plays: Int = 0,
    ) {
        val dto = MusicDto(user, index, "intro$index", 90, blob).apply {
            this.enabled = enabled
            this.failureCount = failures
            this.playCount = plays
        }
        entityManager.persist(dto)
        entityManager.flush()
    }

    @Test
    @Rollback
    fun `a guild with no intros reports zeroes rather than failing`() {
        val stats = persistence.getGuildIntroStats(guildId, IntroHealth.UNHEALTHY_AFTER_FAILURES)

        assertEquals(0L, stats.introCount)
        assertEquals(0L, stats.storedBytes)
        assertEquals(0L, stats.totalPlays)
    }

    @Test
    @Rollback
    fun `the report totals only the guild it was asked about`() {
        val alice = persistUser(1L, guildId)
        val bob = persistUser(2L, guildId)
        val elsewhere = persistUser(3L, otherGuildId)

        persistIntro(alice, 1, ByteArray(100), plays = 5)
        persistIntro(alice, 2, ByteArray(200), plays = 3, enabled = false)
        persistIntro(bob, 1, ByteArray(300), plays = 1, failures = IntroHealth.UNHEALTHY_AFTER_FAILURES)
        // Must not leak into the totals.
        persistIntro(elsewhere, 1, ByteArray(9999), plays = 99)

        val stats = persistence.getGuildIntroStats(guildId, IntroHealth.UNHEALTHY_AFTER_FAILURES)

        assertEquals(3L, stats.introCount)
        assertEquals(2L, stats.userCount)
        // octet_length over the three blobs, not the fourth.
        assertEquals(600L, stats.storedBytes)
        assertEquals(9L, stats.totalPlays)
        assertEquals(1L, stats.disabledCount)
        assertEquals(1L, stats.brokenCount)
    }

    @Test
    @Rollback
    fun `an intro one failure short of the threshold is not counted as broken`() {
        val alice = persistUser(4L, guildId)
        persistIntro(alice, 1, ByteArray(10), failures = IntroHealth.UNHEALTHY_AFTER_FAILURES - 1)

        val stats = persistence.getGuildIntroStats(guildId, IntroHealth.UNHEALTHY_AFTER_FAILURES)

        assertEquals(0L, stats.brokenCount)
    }

    @Test
    @Rollback
    fun `a link intro contributes only its url to the storage total`() {
        // The distinction the report's footer draws: uploads are what grow the
        // table, links are a couple of dozen bytes.
        val alice = persistUser(5L, guildId)
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ".toByteArray()
        persistIntro(alice, 1, url)

        val stats = persistence.getGuildIntroStats(guildId, IntroHealth.UNHEALTHY_AFTER_FAILURES)

        assertEquals(url.size.toLong(), stats.storedBytes)
    }
}
