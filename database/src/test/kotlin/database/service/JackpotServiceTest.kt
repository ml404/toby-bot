package database.service

import database.dto.ConfigDto
import database.dto.TobyCoinJackpotDto
import database.persistence.TobyCoinJackpotPersistence
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JackpotServiceTest {

    private val guildId = 42L

    private lateinit var persistence: TobyCoinJackpotPersistence
    private lateinit var configService: ConfigService
    private lateinit var service: JackpotService

    @BeforeEach
    fun setup() {
        persistence = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        // Default: no `JACKPOT_WIN_PCT` row in the DB → fall through to
        // [JackpotHelper.DEFAULT_WIN_PROBABILITY] (0.01 → 1.0%).
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                guildId.toString()
            )
        } returns null
        service = JackpotService(persistence, configService)
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
    fun `awardJackpot returns the prior pool and zeroes the row`() {
        val existing = TobyCoinJackpotDto(guildId = guildId, pool = 1_500L)
        every { persistence.getByGuildForUpdate(guildId) } returns existing
        every { persistence.upsert(any()) } answers { firstArg() }

        val won = service.awardJackpot(guildId)

        assertEquals(1_500L, won, "winner banks the entire pool")
        assertEquals(0L, existing.pool, "pool resets in the same transaction")
    }

    @Test
    fun `awardJackpot is a no-op (returns 0) when the pool is empty`() {
        val empty = TobyCoinJackpotDto(guildId = guildId, pool = 0L)
        every { persistence.getByGuildForUpdate(guildId) } returns empty

        assertEquals(0L, service.awardJackpot(guildId))
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
        // Validator on the moderation page should already reject anything
        // above 50, but defend against a stale row from before the cap was
        // tightened — JackpotHelper clamps at MAX_WIN_PROBABILITY = 0.5.
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
}
