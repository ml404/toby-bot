package database.service

import database.dto.ConfigDto
import database.dto.TobyCoinJackpotDto
import database.dto.TobyCoinJackpotWinnerDto
import database.persistence.TobyCoinJackpotPersistence
import database.persistence.TobyCoinJackpotWinnerPersistence
import database.persistence.VoiceCreditDailyPersistence
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class JackpotServiceTest {

    private val guildId = 42L
    private val discordId = 7L
    private val now: Instant = Instant.parse("2026-05-08T12:00:00Z")

    private lateinit var persistence: TobyCoinJackpotPersistence
    private lateinit var configService: ConfigService
    private lateinit var winnerPersistence: TobyCoinJackpotWinnerPersistence
    private lateinit var voiceCreditDailyPersistence: VoiceCreditDailyPersistence
    private lateinit var service: JackpotService

    @BeforeEach
    fun setup() {
        persistence = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        winnerPersistence = mockk(relaxed = true)
        voiceCreditDailyPersistence = mockk(relaxed = true)
        // Default: no `JACKPOT_WIN_PCT` row in the DB → fall through to
        // [JackpotHelper.DEFAULT_WIN_PROBABILITY] (0.01 → 1.0%).
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                guildId.toString()
            )
        } returns null
        // Default: no `JACKPOT_PAYOUT_PCT` row → full pool payout (matches
        // pre-rebalance behaviour for unconfigured guilds).
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_PAYOUT_PCT.configValue,
                guildId.toString()
            )
        } returns null
        // Default: cooldown / activity gates disabled.
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS.configValue,
                guildId.toString()
            )
        } returns null
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS.configValue,
                guildId.toString()
            )
        } returns null
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_ACTIVITY_MIN_DAYS.configValue,
                guildId.toString()
            )
        } returns null
        service = JackpotService(
            persistence,
            configService,
            winnerPersistence,
            voiceCreditDailyPersistence,
        )
    }

    @Test
    fun `getPool returns 0 when no row exists yet`() {
        every { persistence.getByGuild(guildId) } returns null

        assertEquals(0L, service.getPool(guildId))
    }

    @Test
    fun `getPool returns the row's pool`() {
        every { persistence.getByGuild(guildId) } returns TobyCoinJackpotDto(guildId = guildId, pool = 1_234L)

        assertEquals(1_234L, service.getPool(guildId))
    }

    @Test
    fun `addToPool seeds + increments on the first deposit`() {
        // First locked-read returns null (no row), service seeds via upsert,
        // re-reads with the lock and finds the freshly-persisted row.
        val seeded = TobyCoinJackpotDto(guildId = guildId, pool = 0L)
        every { persistence.getByGuildForUpdate(guildId) } returnsMany listOf(null, seeded)
        val saved = slot<TobyCoinJackpotDto>()
        every { persistence.upsert(capture(saved)) } answers { saved.captured }

        val newPool = service.addToPool(guildId, 100L)

        assertEquals(100L, newPool)
        // The seed and the increment both go through upsert, so we expect
        // two writes — verify the final state, not the call shape.
        verify(atLeast = 1) { persistence.upsert(any()) }
        assertEquals(100L, seeded.pool, "seeded row mutated to 100 in place")
    }

    @Test
    fun `addToPool increments an existing row's pool`() {
        val existing = TobyCoinJackpotDto(guildId = guildId, pool = 500L)
        every { persistence.getByGuildForUpdate(guildId) } returns existing
        every { persistence.upsert(any()) } answers { firstArg() }

        val newPool = service.addToPool(guildId, 250L)

        assertEquals(750L, newPool)
        assertEquals(750L, existing.pool, "row mutation matches the increment")
    }

    @Test
    fun `addToPool ignores non-positive amounts`() {
        every { persistence.getByGuild(guildId) } returns TobyCoinJackpotDto(guildId = guildId, pool = 99L)

        assertEquals(99L, service.addToPool(guildId, 0L))
        assertEquals(99L, service.addToPool(guildId, -10L))

        verify(exactly = 0) { persistence.getByGuildForUpdate(any()) }
        verify(exactly = 0) { persistence.upsert(any()) }
    }

    @Test
    fun `awardJackpot pays the entire pool when JACKPOT_PAYOUT_PCT is unset`() {
        val existing = TobyCoinJackpotDto(guildId = guildId, pool = 1_500L)
        every { persistence.getByGuildForUpdate(guildId) } returns existing
        every { persistence.upsert(any()) } answers { firstArg() }

        val won = service.awardJackpot(guildId)

        assertEquals(1_500L, won, "default payout pct = 100% pays the entire pool")
        assertEquals(0L, existing.pool, "pool resets in the same transaction")
    }

    @Test
    fun `awardJackpot pays a configured fraction and re-seeds the remainder`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_PAYOUT_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "30", guildId = guildId.toString())
        val existing = TobyCoinJackpotDto(guildId = guildId, pool = 1_000L)
        every { persistence.getByGuildForUpdate(guildId) } returns existing
        every { persistence.upsert(any()) } answers { firstArg() }

        val won = service.awardJackpot(guildId)

        assertEquals(300L, won, "30% of 1000 paid out")
        assertEquals(700L, existing.pool, "remainder re-seeds the next cycle")
    }

    @Test
    fun `awardJackpot is a no-op (returns 0) when the pool is empty`() {
        val empty = TobyCoinJackpotDto(guildId = guildId, pool = 0L)
        every { persistence.getByGuildForUpdate(guildId) } returns empty

        assertEquals(0L, service.awardJackpot(guildId))
        verify(exactly = 0) { persistence.upsert(any()) }
    }

    @Test
    fun `resetPool drains the pool to zero and returns the prior amount`() {
        val existing = TobyCoinJackpotDto(guildId = guildId, pool = 9_999L)
        every { persistence.getByGuildForUpdate(guildId) } returns existing
        every { persistence.upsert(any()) } answers { firstArg() }

        val drained = service.resetPool(guildId)

        assertEquals(9_999L, drained)
        assertEquals(0L, existing.pool)
        verify(exactly = 1) { persistence.upsert(existing) }
    }

    @Test
    fun `resetPool is a no-op when the pool is already empty`() {
        val empty = TobyCoinJackpotDto(guildId = guildId, pool = 0L)
        every { persistence.getByGuildForUpdate(guildId) } returns empty

        assertEquals(0L, service.resetPool(guildId))
        verify(exactly = 0) { persistence.upsert(any()) }
    }

    @Test
    fun `winProbabilityPct returns the default 1 percent when no JACKPOT_WIN_PCT row exists`() {
        // setup() already stubs configService to return null for this key.
        // [JackpotHelper.DEFAULT_WIN_PROBABILITY] = 0.01 → 1.0%.
        assertEquals(1.0, service.winProbabilityPct(guildId), 1e-9)
    }

    @Test
    fun `winProbabilityPct echoes the admin-set percent value`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
            value = "5",
            guildId = guildId.toString()
        )
        assertEquals(5.0, service.winProbabilityPct(guildId), 1e-9)
    }

    @Test
    fun `winProbabilityPct accepts decimal percents like 1·5`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
            value = "1.5",
            guildId = guildId.toString()
        )
        assertEquals(1.5, service.winProbabilityPct(guildId), 1e-9)
    }

    @Test
    fun `winProbabilityPct clamps absurd admin values to MAX_WIN_PROBABILITY`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
            value = "999",
            guildId = guildId.toString()
        )
        assertEquals(50.0, service.winProbabilityPct(guildId), 1e-9)
    }

    @Test
    fun `winProbabilityPct falls back to the default when the row is unparseable`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
            value = "wibble",
            guildId = guildId.toString()
        )
        assertEquals(1.0, service.winProbabilityPct(guildId), 1e-9)
    }

    @Test
    fun `winProbabilityDisplay renders the default percent without trailing zeros`() {
        // setup() leaves the config row null → DEFAULT_WIN_PROBABILITY 0.01
        // → 1.0 percent. The banner should read "1", not "1.00".
        assertEquals("1", service.winProbabilityDisplay(guildId))
    }

    @Test
    fun `winProbabilityDisplay preserves sub-percent precision admins set`() {
        // Regression for the user-reported bug: saved 0.0005 used to
        // collapse to "0.01" via formatDecimal(value, 1, 2). With the
        // string-formatter the banner shows "0.0005" instead.
        listOf(
            "0.5" to "0.5",
            "0.05" to "0.05",
            "0.005" to "0.005",
            "0.0005" to "0.0005",
        ).forEach { (saved, expected) ->
            every {
                configService.getConfigByName(
                    ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                    guildId.toString()
                )
            } returns ConfigDto(
                name = ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                value = saved,
                guildId = guildId.toString()
            )
            assertEquals(
                expected,
                service.winProbabilityDisplay(guildId),
                "saved '$saved' should display as '$expected' on the banner"
            )
        }
    }

    @Test
    fun `winProbabilityDisplay renders zero as a single 0`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
            value = "0",
            guildId = guildId.toString()
        )
        assertEquals("0", service.winProbabilityDisplay(guildId))
    }

    @Test
    fun `winProbabilityDisplay echoes whole-number percents without a decimal point`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
            value = "5",
            guildId = guildId.toString()
        )
        assertEquals("5", service.winProbabilityDisplay(guildId))
    }

    @Test
    fun `stakeAnchor returns the default 500 when no JACKPOT_STAKE_ANCHOR row exists`() {
        // setup() already stubs configService to return null for this key.
        // [JackpotHelper.DEFAULT_STAKE_ANCHOR] = 500.
        assertEquals(500L, service.stakeAnchor(guildId))
    }

    @Test
    fun `stakeAnchor echoes the admin-set whole-number value`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR.configValue,
            value = "1500",
            guildId = guildId.toString()
        )
        assertEquals(1500L, service.stakeAnchor(guildId))
    }

    @Test
    fun `stakeAnchor coerces zero up to 1 to keep it out of the divisor`() {
        // JackpotHelper.rollOnWin divides by the anchor — 0 would NaN
        // the scaling factor. Helper coerces to >= 1; test pins that
        // the public service method preserves that contract.
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR.configValue,
            value = "0",
            guildId = guildId.toString()
        )
        assertEquals(1L, service.stakeAnchor(guildId))
    }

    @Test
    fun `stakeAnchor falls back to the default when the row is unparseable`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR.configValue,
                guildId.toString()
            )
        } returns ConfigDto(
            name = ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR.configValue,
            value = "wibble",
            guildId = guildId.toString()
        )
        assertEquals(500L, service.stakeAnchor(guildId))
    }

    // ---- recordWin / isOnCooldown ----

    @Test
    fun `recordWin upserts a winner row at the supplied timestamp`() {
        val captured = slot<TobyCoinJackpotWinnerDto>()
        every { winnerPersistence.upsert(capture(captured)) } answers { captured.captured }

        service.recordWin(guildId, discordId, 250L, at = now)

        assertEquals(guildId, captured.captured.guildId)
        assertEquals(discordId, captured.captured.discordId)
        assertEquals(now, captured.captured.lastWonAt)
        assertEquals(250L, captured.captured.lastWonAmount)
    }

    @Test
    fun `recordWin is a no-op for non-positive amounts`() {
        service.recordWin(guildId, discordId, 0L)
        service.recordWin(guildId, discordId, -10L)
        verify(exactly = 0) { winnerPersistence.upsert(any()) }
    }

    @Test
    fun `isOnCooldown returns false when the cooldown config is unset (gate disabled)`() {
        // setup() stubs the config to null — gate disabled.
        assertFalse(service.isOnCooldown(guildId, discordId, at = now))
        verify(exactly = 0) { winnerPersistence.get(any(), any()) }
    }

    @Test
    fun `isOnCooldown returns false when the user has no prior win row`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "14", guildId = guildId.toString())
        every { winnerPersistence.get(guildId, discordId) } returns null

        assertFalse(service.isOnCooldown(guildId, discordId, at = now))
    }

    @Test
    fun `isOnCooldown returns true when last win is within the configured window`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "14", guildId = guildId.toString())
        every { winnerPersistence.get(guildId, discordId) } returns TobyCoinJackpotWinnerDto(
            guildId = guildId,
            discordId = discordId,
            lastWonAt = now.minus(Duration.ofDays(7)),
            lastWonAmount = 100L,
        )

        assertTrue(service.isOnCooldown(guildId, discordId, at = now))
    }

    @Test
    fun `isOnCooldown returns false when last win is outside the window`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "14", guildId = guildId.toString())
        every { winnerPersistence.get(guildId, discordId) } returns TobyCoinJackpotWinnerDto(
            guildId = guildId,
            discordId = discordId,
            lastWonAt = now.minus(Duration.ofDays(20)),
            lastWonAmount = 100L,
        )

        assertFalse(service.isOnCooldown(guildId, discordId, at = now))
    }

    // ---- isActive ----

    @Test
    fun `isActive returns true when the activity gate config is unset (disabled)`() {
        assertTrue(service.isActive(guildId, discordId, at = now))
        verify(exactly = 0) { voiceCreditDailyPersistence.countDaysSince(any(), any(), any()) }
    }

    @Test
    fun `isActive returns true when activity meets the minimum threshold`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "7", guildId = guildId.toString())
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_ACTIVITY_MIN_DAYS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "3", guildId = guildId.toString())
        every { voiceCreditDailyPersistence.countDaysSince(eq(discordId), eq(guildId), any()) } returns 4L

        assertTrue(service.isActive(guildId, discordId, at = now))
    }

    @Test
    fun `isActive returns false when activity is below the minimum threshold`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "7", guildId = guildId.toString())
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_ACTIVITY_MIN_DAYS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "3", guildId = guildId.toString())
        every { voiceCreditDailyPersistence.countDaysSince(eq(discordId), eq(guildId), any()) } returns 2L

        assertFalse(service.isActive(guildId, discordId, at = now))
    }
}
