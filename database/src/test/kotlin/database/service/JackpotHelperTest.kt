package database.service

import database.dto.ConfigDto
import database.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class JackpotHelperTest {

    private val guildId = 200L
    private val discordId = 7L
    private val MAX_STAKE = 1_000L

    private lateinit var jackpotService: JackpotService
    private lateinit var userService: UserService
    private lateinit var configService: ConfigService

    @BeforeEach
    fun setup() {
        jackpotService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        // Default the post-fraud gates to "pass" so the legacy probability
        // tests below stay focused on the random-roll mechanics. Gate-
        // specific tests override these stubs below. The third matcher
        // covers the `at: Instant = Instant.now()` default parameter — the
        // bytecode call always has three args even when callers omit it.
        every { jackpotService.isOnCooldown(any(), any(), any()) } returns false
        every { jackpotService.isActive(any(), any(), any()) } returns true
    }

    private fun configReturns(value: String?) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue,
                guildId.toString()
            )
        } returns value?.let { ConfigDto(name = "x", value = it, guildId = guildId.toString()) }
    }

    private fun winConfigReturns(value: String?) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WIN_PCT.configValue,
                guildId.toString()
            )
        } returns value?.let { ConfigDto(name = "x", value = it, guildId = guildId.toString()) }
    }

    private fun anchorConfigReturns(value: String?) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_STAKE_ANCHOR.configValue,
                guildId.toString()
            )
        } returns value?.let { ConfigDto(name = "x", value = it, guildId = guildId.toString()) }
    }

    private fun stubRandom(value: Double): Random {
        val r = mockk<Random>()
        every { r.nextDouble() } returns value
        return r
    }

    private fun freshUser(initial: Long = 0L): UserDto =
        UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = initial }

    @Test
    fun `lossTributeRate returns the default fraction when no config row exists`() {
        configReturns(null)

        assertEquals(JackpotHelper.DEFAULT_LOSS_TRIBUTE, JackpotHelper.lossTributeRate(configService, guildId))
    }

    @Test
    fun `lossTributeRate parses whole-number percent into a fraction`() {
        configReturns("25")

        assertEquals(0.25, JackpotHelper.lossTributeRate(configService, guildId), 1e-9)
    }

    @Test
    fun `lossTributeRate clamps above MAX_LOSS_TRIBUTE`() {
        configReturns("99")

        assertEquals(JackpotHelper.MAX_LOSS_TRIBUTE, JackpotHelper.lossTributeRate(configService, guildId))
    }

    @Test
    fun `lossTributeRate falls back to default on unparseable value`() {
        configReturns("bogus")

        assertEquals(JackpotHelper.DEFAULT_LOSS_TRIBUTE, JackpotHelper.lossTributeRate(configService, guildId))
    }

    @Test
    fun `lossTributeRate accepts zero (admin disables tribute entirely)`() {
        configReturns("0")

        assertEquals(0.0, JackpotHelper.lossTributeRate(configService, guildId), 1e-9)
    }

    @Test
    fun `divertOnLoss deposits floor(stake * default rate) into the pool`() {
        configReturns(null)  // 10 % default

        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = 100L)

        assertEquals(10L, tribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    @Test
    fun `divertOnLoss honours the per-guild override`() {
        configReturns("25")  // 25 %

        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = 200L)

        assertEquals(50L, tribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 50L) }
    }

    @Test
    fun `divertOnLoss is a no-op when admin set rate to zero`() {
        configReturns("0")

        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = 1_000L)

        assertEquals(0L, tribute)
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
    }

    @Test
    fun `divertOnLoss is a no-op when the floor produces zero`() {
        configReturns(null)  // 10 % default → floor(9 * 0.10) = 0

        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = 9L)

        assertEquals(0L, tribute)
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
    }

    @Test
    fun `divertOnLoss ignores non-positive stakes`() {
        configReturns(null)

        assertEquals(0L, JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = 0L))
        assertEquals(0L, JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = -10L))
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
    }

    // ---- winProbability ----

    @Test
    fun `winProbability returns the default when no config row exists`() {
        winConfigReturns(null)

        assertEquals(JackpotHelper.DEFAULT_WIN_PROBABILITY, JackpotHelper.winProbability(configService, guildId), 1e-9)
    }

    @Test
    fun `winProbability parses a sub-1 percent decimal`() {
        winConfigReturns("0.5")

        assertEquals(0.005, JackpotHelper.winProbability(configService, guildId), 1e-9)
    }

    @Test
    fun `winProbability parses a whole-number percent`() {
        winConfigReturns("5")

        assertEquals(0.05, JackpotHelper.winProbability(configService, guildId), 1e-9)
    }

    @Test
    fun `winProbability clamps above MAX_WIN_PROBABILITY`() {
        winConfigReturns("100")

        assertEquals(JackpotHelper.MAX_WIN_PROBABILITY, JackpotHelper.winProbability(configService, guildId), 1e-9)
    }

    @Test
    fun `winProbability falls back to default on unparseable, NaN, or negative values`() {
        winConfigReturns("bogus")
        assertEquals(JackpotHelper.DEFAULT_WIN_PROBABILITY, JackpotHelper.winProbability(configService, guildId), 1e-9)

        winConfigReturns("NaN")
        assertEquals(JackpotHelper.DEFAULT_WIN_PROBABILITY, JackpotHelper.winProbability(configService, guildId), 1e-9)

        winConfigReturns("-5")
        assertEquals(JackpotHelper.DEFAULT_WIN_PROBABILITY, JackpotHelper.winProbability(configService, guildId), 1e-9)
    }

    @Test
    fun `winProbability accepts zero (admin disables jackpot wins)`() {
        winConfigReturns("0")

        assertEquals(0.0, JackpotHelper.winProbability(configService, guildId), 1e-9)
    }

    // ---- rollOnWin ----

    @Test
    fun `rollOnWin uses default 1 percent when config unset and roll lands inside threshold`() {
        winConfigReturns(null)
        anchorConfigReturns(MAX_STAKE.toString())
        every { jackpotService.awardJackpot(guildId) } returns 500L
        val user = freshUser(initial = 100L)

        val won = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = MAX_STAKE, random = stubRandom(0.001)
        )

        assertEquals(500L, won)
        assertEquals(600L, user.socialCredit)
        verify(exactly = 1) { userService.updateUser(user) }
    }

    @Test
    fun `rollOnWin misses when roll equals or exceeds the configured probability`() {
        winConfigReturns(null)  // 1 % default
        anchorConfigReturns(MAX_STAKE.toString())
        val user = freshUser(initial = 100L)

        // 0.5 is far above the 0.01 threshold.
        val won = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = MAX_STAKE, random = stubRandom(0.5)
        )

        assertEquals(0L, won)
        assertEquals(100L, user.socialCredit)
        verify(exactly = 0) { jackpotService.awardJackpot(any()) }
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `rollOnWin honours a sub-1 percent admin override`() {
        winConfigReturns("0.5")  // 0.005
        anchorConfigReturns(MAX_STAKE.toString())
        every { jackpotService.awardJackpot(guildId) } returns 1_000L
        val userHit = freshUser()
        val userMiss = freshUser()

        val hit = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, userHit, guildId,
            stake = MAX_STAKE, random = stubRandom(0.004)
        )
        val miss = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, userMiss, guildId,
            stake = MAX_STAKE, random = stubRandom(0.006)
        )

        assertEquals(1_000L, hit, "0.004 < 0.005 should hit")
        assertEquals(0L, miss, "0.006 >= 0.005 should miss")
    }

    @Test
    fun `rollOnWin disabled when admin set rate to zero`() {
        winConfigReturns("0")
        anchorConfigReturns(MAX_STAKE.toString())
        val user = freshUser(initial = 100L)

        // Even an absurdly small roll can't beat a 0.0 threshold (`< 0.0` is unreachable).
        val won = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = MAX_STAKE, random = stubRandom(0.0)
        )

        assertEquals(0L, won)
        verify(exactly = 0) { jackpotService.awardJackpot(any()) }
    }

    @Test
    fun `rollOnWin clamps above MAX_WIN_PROBABILITY`() {
        winConfigReturns("100")  // clamps to 0.50
        anchorConfigReturns(MAX_STAKE.toString())
        every { jackpotService.awardJackpot(guildId) } returns 50L
        val userHit = freshUser()
        val userMiss = freshUser()

        val hit = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, userHit, guildId,
            stake = MAX_STAKE, random = stubRandom(0.49)
        )
        val miss = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, userMiss, guildId,
            stake = MAX_STAKE, random = stubRandom(0.51)
        )

        assertEquals(50L, hit)
        assertEquals(0L, miss)
    }

    @Test
    fun `rollOnWin returns zero and does not credit user when pool is empty`() {
        winConfigReturns("1")
        anchorConfigReturns(MAX_STAKE.toString())
        every { jackpotService.awardJackpot(guildId) } returns 0L
        val user = freshUser(initial = 100L)

        val won = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = MAX_STAKE, random = stubRandom(0.001)
        )

        assertEquals(0L, won)
        assertEquals(100L, user.socialCredit, "balance must be untouched when pool was empty")
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    // ---- stake-weighted scaling ----

    @Test
    fun `rollOnWin scales by stake fraction so a min-wager autoclick is effectively zero`() {
        winConfigReturns("1") // 1 % base → 10/1000 → 0.01 % effective
        anchorConfigReturns("1000")
        every { jackpotService.awardJackpot(guildId) } returns 1_000L
        val userHit = freshUser()
        val userMiss = freshUser()

        val hit = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, userHit, guildId,
            stake = 10L, random = stubRandom(0.00009)
        )
        val miss = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, userMiss, guildId,
            stake = 10L, random = stubRandom(0.0001)
        )

        assertEquals(1_000L, hit, "0.00009 < 0.0001 should hit")
        assertEquals(0L, miss, "0.0001 >= 0.0001 should miss")
    }

    @Test
    fun `rollOnWin at the anchor stake rolls at the unscaled base probability`() {
        winConfigReturns("1") // 1 % base → 1000/1000 → 1 % effective
        anchorConfigReturns("1000")
        every { jackpotService.awardJackpot(guildId) } returns 1_000L
        val userHit = freshUser()
        val userMiss = freshUser()

        val hit = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, userHit, guildId,
            stake = 1_000L, random = stubRandom(0.009)
        )
        val miss = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, userMiss, guildId,
            stake = 1_000L, random = stubRandom(0.011)
        )

        assertEquals(1_000L, hit)
        assertEquals(0L, miss)
    }

    @Test
    fun `rollOnWin caps scale at 1 when stake exceeds the anchor`() {
        // Once stake >= anchor, the anchor stops shrinking the probability —
        // bigger bets don't get *more* than the base rate. This is the new
        // semantics that lets admins raise per-game max stake without the
        // probability also unbounded-scaling.
        winConfigReturns("1") // base 1 %
        anchorConfigReturns("1000")
        every { jackpotService.awardJackpot(guildId) } returns 100L
        val user = freshUser()

        val won = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = 10_000L, random = stubRandom(0.009)
        )

        assertEquals(100L, won)
    }

    @Test
    fun `rollOnWin defaults the anchor to 500 when config row missing`() {
        winConfigReturns("1")        // 1 % base
        anchorConfigReturns(null)    // fall through to DEFAULT_STAKE_ANCHOR (500)
        every { jackpotService.awardJackpot(guildId) } returns 1L
        val user = freshUser()

        // stake/anchor = 500/500 = 1.0 → effective = 1 %; 0.009 < 0.01 hits.
        val won = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = 500L, random = stubRandom(0.009)
        )

        assertEquals(1L, won)
    }

    @Test
    fun `rollOnWin coerces a zero anchor up to 1 (defensive divide-by-zero guard)`() {
        // An admin who sets JACKPOT_STAKE_ANCHOR=0 shouldn't divide-by-zero
        // the probability formula. stakeAnchor() coerces to >= 1, so any
        // stake >= 1 trivially saturates the scale to 1.
        winConfigReturns("1")
        anchorConfigReturns("0")
        every { jackpotService.awardJackpot(guildId) } returns 100L
        val user = freshUser()

        val won = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = 100L, random = stubRandom(0.009)
        )

        assertEquals(100L, won)
    }

    // ---- post-fraud gates: cooldown, activity, recordWin ----

    @Test
    fun `rollOnWin blocks payout when winner is on cooldown`() {
        winConfigReturns("1")
        anchorConfigReturns(MAX_STAKE.toString())
        every { jackpotService.isOnCooldown(guildId, discordId, any()) } returns true
        val user = freshUser(initial = 100L)

        val won = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = MAX_STAKE, random = stubRandom(0.001)
        )

        assertEquals(0L, won, "blocked by cooldown gate")
        assertEquals(100L, user.socialCredit, "balance untouched on blocked gate")
        verify(exactly = 0) { jackpotService.awardJackpot(any()) }
        verify(exactly = 0) { jackpotService.recordWin(any(), any(), any(), any()) }
    }

    @Test
    fun `rollOnWin blocks payout when user is not active enough`() {
        winConfigReturns("1")
        anchorConfigReturns(MAX_STAKE.toString())
        every { jackpotService.isActive(guildId, discordId, any()) } returns false
        val user = freshUser(initial = 100L)

        val won = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = MAX_STAKE, random = stubRandom(0.001)
        )

        assertEquals(0L, won, "blocked by activity gate")
        verify(exactly = 0) { jackpotService.awardJackpot(any()) }
        verify(exactly = 0) { jackpotService.recordWin(any(), any(), any(), any()) }
    }

    @Test
    fun `rollOnWin records the win for cooldown tracking on a successful payout`() {
        winConfigReturns("1")
        anchorConfigReturns(MAX_STAKE.toString())
        every { jackpotService.awardJackpot(guildId) } returns 250L
        val user = freshUser()

        val won = JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = MAX_STAKE, random = stubRandom(0.001)
        )

        assertEquals(250L, won)
        verify(exactly = 1) { jackpotService.recordWin(guildId, discordId, 250L, any()) }
    }

    @Test
    fun `rollOnWin does not record a no-op award (empty pool)`() {
        winConfigReturns("1")
        anchorConfigReturns(MAX_STAKE.toString())
        every { jackpotService.awardJackpot(guildId) } returns 0L
        val user = freshUser()

        JackpotHelper.rollOnWin(
            jackpotService, configService, userService, user, guildId,
            stake = MAX_STAKE, random = stubRandom(0.001)
        )

        verify(exactly = 0) { jackpotService.recordWin(any(), any(), any(), any()) }
    }

    // ---- payoutPct ----

    @Test
    fun `payoutPct returns the default fraction when no config row exists`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_PAYOUT_PCT.configValue,
                guildId.toString()
            )
        } returns null
        assertEquals(JackpotHelper.DEFAULT_PAYOUT_PCT, JackpotHelper.payoutPct(configService, guildId), 1e-9)
    }

    @Test
    fun `payoutPct parses whole-number percent and clamps to 1 at 100 percent`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_PAYOUT_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "30", guildId = guildId.toString())
        assertEquals(0.30, JackpotHelper.payoutPct(configService, guildId), 1e-9)
    }

    @Test
    fun `payoutPct treats zero or negative as missing (falls back to default)`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_PAYOUT_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "0", guildId = guildId.toString())
        assertEquals(JackpotHelper.DEFAULT_PAYOUT_PCT, JackpotHelper.payoutPct(configService, guildId), 1e-9)

        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_PAYOUT_PCT.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "-10", guildId = guildId.toString())
        assertEquals(JackpotHelper.DEFAULT_PAYOUT_PCT, JackpotHelper.payoutPct(configService, guildId), 1e-9)
    }

    // ---- winnerCooldownDays ----

    @Test
    fun `winnerCooldownDays defaults to disabled when unset`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS.configValue,
                guildId.toString()
            )
        } returns null
        assertEquals(0L, JackpotHelper.winnerCooldownDays(configService, guildId))
    }

    @Test
    fun `winnerCooldownDays clamps to MAX_WINNER_COOLDOWN_DAYS`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_WINNER_COOLDOWN_DAYS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "9999", guildId = guildId.toString())
        assertEquals(JackpotHelper.MAX_WINNER_COOLDOWN_DAYS, JackpotHelper.winnerCooldownDays(configService, guildId))
    }

    // ---- activityWindowDays / activityMinDays ----

    @Test
    fun `activityWindowDays defaults to disabled when unset`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_ACTIVITY_WINDOW_DAYS.configValue,
                guildId.toString()
            )
        } returns null
        assertEquals(0L, JackpotHelper.activityWindowDays(configService, guildId))
    }

    @Test
    fun `activityMinDays coerces to at least 1`() {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_ACTIVITY_MIN_DAYS.configValue,
                guildId.toString()
            )
        } returns ConfigDto(name = "x", value = "0", guildId = guildId.toString())
        assertEquals(1L, JackpotHelper.activityMinDays(configService, guildId))
    }
}
