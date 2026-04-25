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

        // Fixed reference point for deterministic windowing math. Avoids
        // Instant.now()-based off-by-microsecond drift between the value
        // stored in H2 and the same value used as a query bound — that
        // difference made the recorded row drop out of `executedAt >= since`
        // when `since` was computed off the same `now`.
        private val REF: Instant = Instant.parse("2026-06-15T12:00:00Z")
    }

    @Test
    fun `record then listSince returns the row`() {
        val guildId = freshGuildId()
        val executed = REF
        persistence.record(
            TobyCoinTradeDto(
                guildId = guildId,
                discordId = 111L,
                side = "BUY",
                amount = 5L,
                pricePerCoin = 12.5,
                executedAt = executed
            )
        )

        // Use EPOCH as the floor so we don't depend on H2's precision when
        // comparing executedAt to a since value computed off the same Instant.
        val rows = persistence.listSince(guildId, Instant.EPOCH)

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
        // Two rows: one inside the 1h window, one well outside it.
        persistence.record(
            TobyCoinTradeDto(
                guildId = guildId, discordId = 1L, side = "BUY", amount = 1L,
                pricePerCoin = 10.0, executedAt = REF.minus(Duration.ofMinutes(10))
            )
        )
        persistence.record(
            TobyCoinTradeDto(
                guildId = guildId, discordId = 2L, side = "SELL", amount = 2L,
                pricePerCoin = 11.0, executedAt = REF.minus(Duration.ofDays(2))
            )
        )

        val recent = persistence.listSince(guildId, REF.minus(Duration.ofHours(1)))

        assertEquals(1, recent.size, "older row must be excluded")
        assertEquals(1L, recent.single().discordId)
    }

    @Test
    fun `deleteOlderThan removes pre-cutoff rows`() {
        val guildId = freshGuildId()
        persistence.record(
            TobyCoinTradeDto(
                guildId = guildId, discordId = 1L, side = "BUY", amount = 1L,
                pricePerCoin = 10.0, executedAt = REF.minus(Duration.ofDays(40))
            )
        )
        persistence.record(
            TobyCoinTradeDto(
                guildId = guildId, discordId = 2L, side = "BUY", amount = 1L,
                pricePerCoin = 10.0, executedAt = REF.minus(Duration.ofDays(5))
            )
        )

        val removed = persistence.deleteOlderThan(REF.minus(Duration.ofDays(30)))

        assertTrue(removed >= 1, "expected at least the 40-day-old row to be deleted")
        val survivors = persistence.listSince(guildId, Instant.EPOCH)
        assertEquals(1, survivors.size, "the 5-day-old row should survive")
        assertEquals(2L, survivors.single().discordId)
    }
}
