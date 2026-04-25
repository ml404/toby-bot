package integration.database

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import database.dto.TobyCoinTradeDto
import database.persistence.TobyCoinTradePersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Verifies the trade ledger persistence end-to-end against H2 + real JPA.
 * The market chart's hover markers and "Recent trades" list both depend on
 * [TobyCoinTradePersistence.listSince] returning rows in chronological order
 * within the requested window, and on [TobyCoinTradePersistence.deleteOlderThan]
 * actually clearing aged rows so the 30-day retention claim in privacy.html
 * holds.
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
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class TobyCoinTradePersistenceIntegrationTest {

    @Autowired
    lateinit var persistence: TobyCoinTradePersistence

    companion object {
        private val seq = AtomicLong()
        private fun freshGuildId() = 800_000L + seq.incrementAndGet()
    }

    @Test
    fun `record then listSince returns the row`() {
        val guildId = freshGuildId()
        val now = Instant.now()
        persistence.record(
            TobyCoinTradeDto(
                guildId = guildId,
                discordId = 111L,
                side = "BUY",
                amount = 5L,
                pricePerCoin = 12.5,
                executedAt = now
            )
        )

        val rows = persistence.listSince(guildId, now.minus(Duration.ofMinutes(1)))

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(111L, row.discordId)
        assertEquals("BUY", row.side)
        assertEquals(5L, row.amount)
        assertEquals(12.5, row.pricePerCoin, 1e-9)
    }

    @Test
    fun `listSince filters by window`() {
        val guildId = freshGuildId()
        val now = Instant.now()
        // Two rows: one inside the 1h window, one well outside it.
        persistence.record(
            TobyCoinTradeDto(
                guildId = guildId, discordId = 1L, side = "BUY", amount = 1L,
                pricePerCoin = 10.0, executedAt = now.minus(Duration.ofMinutes(10))
            )
        )
        persistence.record(
            TobyCoinTradeDto(
                guildId = guildId, discordId = 2L, side = "SELL", amount = 2L,
                pricePerCoin = 11.0, executedAt = now.minus(Duration.ofDays(2))
            )
        )

        val recent = persistence.listSince(guildId, now.minus(Duration.ofHours(1)))

        assertEquals(1, recent.size, "older row must be excluded")
        assertEquals(1L, recent.single().discordId)
    }

    @Test
    fun `deleteOlderThan removes pre-cutoff rows`() {
        val guildId = freshGuildId()
        val now = Instant.now()
        persistence.record(
            TobyCoinTradeDto(
                guildId = guildId, discordId = 1L, side = "BUY", amount = 1L,
                pricePerCoin = 10.0, executedAt = now.minus(Duration.ofDays(40))
            )
        )
        persistence.record(
            TobyCoinTradeDto(
                guildId = guildId, discordId = 2L, side = "BUY", amount = 1L,
                pricePerCoin = 10.0, executedAt = now.minus(Duration.ofDays(5))
            )
        )

        val removed = persistence.deleteOlderThan(now.minus(Duration.ofDays(30)))

        assertTrue(removed >= 1, "expected at least the 40-day-old row to be deleted")
        val survivors = persistence.listSince(guildId, now.minus(Duration.ofDays(365)))
        assertEquals(1, survivors.size, "the 5-day-old row should survive")
        assertEquals(2L, survivors.single().discordId)
    }
}
