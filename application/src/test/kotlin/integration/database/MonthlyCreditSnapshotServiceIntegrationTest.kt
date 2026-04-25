package integration.database

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import database.dto.MonthlyCreditSnapshotDto
import database.service.MonthlyCreditSnapshotService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate

/**
 * Verifies that the monthly snapshot round-trips BOTH `socialCredit` and
 * `tobyCoins` through the JPA layer. The wallet "+/- this month" delta depends
 * on `tobyCoins` persisting through [MonthlyCreditSnapshotService.upsert] — if
 * the update branch of upsert forgets to copy the field, every subsequent
 * month's delta is wrong and no unit test would notice.
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
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class MonthlyCreditSnapshotServiceIntegrationTest {

    @Autowired
    lateinit var service: MonthlyCreditSnapshotService

    private val guildId = 999_001L
    private val snapshotDate: LocalDate = LocalDate.of(2026, 1, 1)

    @Test
    fun `upsert inserts a new snapshot row with both counters`() {
        val discordId = 111L
        service.upsert(
            MonthlyCreditSnapshotDto(
                discordId = discordId,
                guildId = guildId,
                snapshotDate = snapshotDate,
                socialCredit = 100L,
                tobyCoins = 42L
            )
        )

        val loaded = service.get(discordId, guildId, snapshotDate)
        assertNotNull(loaded, "snapshot should be persisted")
        assertEquals(100L, loaded!!.socialCredit)
        assertEquals(42L, loaded.tobyCoins)
    }

    @Test
    fun `upsert updates both counters on an existing row`() {
        val discordId = 222L
        service.upsert(
            MonthlyCreditSnapshotDto(
                discordId = discordId,
                guildId = guildId,
                snapshotDate = snapshotDate,
                socialCredit = 100L,
                tobyCoins = 42L
            )
        )

        // Same PK, different values — hits the update branch of upsert.
        service.upsert(
            MonthlyCreditSnapshotDto(
                discordId = discordId,
                guildId = guildId,
                snapshotDate = snapshotDate,
                socialCredit = 999L,
                tobyCoins = 77L
            )
        )

        val loaded = service.get(discordId, guildId, snapshotDate)
        assertNotNull(loaded)
        assertEquals(999L, loaded!!.socialCredit,
            "socialCredit must be updated — existing guarantee")
        assertEquals(77L, loaded.tobyCoins,
            "tobyCoins must be updated — regression guard, without the " +
                "`existing.tobyCoins = dto.tobyCoins` line this fails and " +
                "first-of-month deltas become permanently wrong")
    }

    @Test
    fun `listForGuildDate returns both counters for every row`() {
        val date = snapshotDate.plusMonths(1)
        service.upsert(
            MonthlyCreditSnapshotDto(
                discordId = 333L, guildId = guildId, snapshotDate = date,
                socialCredit = 50L, tobyCoins = 5L
            )
        )
        service.upsert(
            MonthlyCreditSnapshotDto(
                discordId = 444L, guildId = guildId, snapshotDate = date,
                socialCredit = 80L, tobyCoins = 9L
            )
        )

        val rows = service.listForGuildDate(guildId, date).associateBy { it.discordId }
        assertEquals(5L, rows[333L]?.tobyCoins)
        assertEquals(50L, rows[333L]?.socialCredit)
        assertEquals(9L, rows[444L]?.tobyCoins)
        assertEquals(80L, rows[444L]?.socialCredit)
    }

    @Test
    fun `upsertIfMissing inserts when absent and is a no-op when present`() {
        val date = snapshotDate.plusMonths(2)
        val discordId = 555L

        // First call: row doesn't exist, should be inserted.
        val inserted = service.upsertIfMissing(
            MonthlyCreditSnapshotDto(
                discordId = discordId, guildId = guildId, snapshotDate = date,
                socialCredit = 100L, tobyCoins = 20L
            )
        )
        assertEquals(100L, inserted.socialCredit)
        assertEquals(20L, inserted.tobyCoins)

        // Second call with different values: must NOT clobber the existing row,
        // because lazy-baseline is meant to snapshot once per month, not
        // overwrite each page load.
        val attempted = service.upsertIfMissing(
            MonthlyCreditSnapshotDto(
                discordId = discordId, guildId = guildId, snapshotDate = date,
                socialCredit = 999L, tobyCoins = 999L
            )
        )
        assertEquals(100L, attempted.socialCredit, "must return existing, not the new dto")
        assertEquals(20L, attempted.tobyCoins)

        // Persisted state matches existing row, not the attempted overwrite.
        val persisted = service.get(discordId, guildId, date)!!
        assertEquals(100L, persisted.socialCredit)
        assertEquals(20L, persisted.tobyCoins)
    }
}
