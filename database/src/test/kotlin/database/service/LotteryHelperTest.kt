package database.service

import database.dto.ConfigDto
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import database.service.guild.ConfigService
import database.service.lottery.LotteryHelper

/**
 * Pure-helper coverage for the participation-incentive tiers added to
 * [LotteryHelper]. [JackpotLotteryServiceTest] covers the wiring; here
 * we pin the parsing / clamping / matching maths so a refactor of the
 * config shape can't quietly change the rules.
 */
class LotteryHelperTest {

    private val guildId = 100L

    private fun cfg(value: String): ConfigDto =
        ConfigDto(name = "x", value = value, guildId = guildId.toString())

    private fun stub(configService: ConfigService, key: ConfigDto.Configurations, raw: String?) {
        every {
            configService.getConfigByName(key.configValue, guildId.toString())
        } returns raw?.let { cfg(it) }
    }

    @Test
    fun `bulkBonusFor returns 0 for empty tiers`() {
        assertEquals(0L, LotteryHelper.bulkBonusFor(100L, emptyList()))
    }

    @Test
    fun `bulkBonusFor returns 0 below the lowest threshold`() {
        assertEquals(0L, LotteryHelper.bulkBonusFor(4L, listOf(5L to 1L, 10L to 3L)))
    }

    @Test
    fun `bulkBonusFor picks the highest matching tier and subsumes lower ones`() {
        val tiers = listOf(5L to 1L, 10L to 3L, 25L to 8L)
        assertEquals(1L, LotteryHelper.bulkBonusFor(5L, tiers))
        assertEquals(3L, LotteryHelper.bulkBonusFor(10L, tiers))
        assertEquals(3L, LotteryHelper.bulkBonusFor(24L, tiers))
        assertEquals(8L, LotteryHelper.bulkBonusFor(25L, tiers))
        assertEquals(8L, LotteryHelper.bulkBonusFor(10_000L, tiers))
    }

    @Test
    fun `multiplierBpFor returns identity below the lowest threshold or for empty tiers`() {
        assertEquals(LotteryHelper.MULTIPLIER_BP_IDENTITY, LotteryHelper.multiplierBpFor(50L, emptyList()))
        assertEquals(
            LotteryHelper.MULTIPLIER_BP_IDENTITY,
            LotteryHelper.multiplierBpFor(4L, listOf(5L to 11_000, 15L to 12_500)),
        )
    }

    @Test
    fun `multiplierBpFor picks the highest matching tier`() {
        val tiers = listOf(5L to 11_000, 15L to 12_500, 40L to 15_000)
        assertEquals(11_000, LotteryHelper.multiplierBpFor(5L, tiers))
        assertEquals(12_500, LotteryHelper.multiplierBpFor(20L, tiers))
        assertEquals(15_000, LotteryHelper.multiplierBpFor(40L, tiers))
        assertEquals(15_000, LotteryHelper.multiplierBpFor(1_000L, tiers))
    }

    @Test
    fun `milestonesBetween fires nothing when newTotal is at or below prevTotal`() {
        val ms = listOf(50L to 5L, 100L to 5L)
        assertTrue(LotteryHelper.milestonesBetween(100L, 100L, ms, 0L).isEmpty())
        assertTrue(LotteryHelper.milestonesBetween(100L, 50L, ms, 0L).isEmpty())
    }

    @Test
    fun `milestonesBetween fires multiple thresholds when one buy crosses several`() {
        val ms = listOf(50L to 5L, 100L to 5L, 250L to 10L)
        val fired = LotteryHelper.milestonesBetween(0L, 100L, ms, 0L)
        assertEquals(listOf(50L to 5L, 100L to 5L), fired)
    }

    @Test
    fun `milestonesBetween skips milestones already fired`() {
        val ms = listOf(50L to 5L, 100L to 5L)
        val fired = LotteryHelper.milestonesBetween(0L, 200L, ms, 50L)
        assertEquals(listOf(100L to 5L), fired, "50 was already fired earlier; only 100 fires now")
    }

    @Test
    fun `bulkBonusTiers ignores tiers with zero threshold and returns ascending`() {
        val cs = mockk<ConfigService>(relaxed = true)
        stub(cs, ConfigDto.Configurations.LOTTERY_BULK_TIER1_BUY, "25")
        stub(cs, ConfigDto.Configurations.LOTTERY_BULK_TIER1_BONUS, "8")
        stub(cs, ConfigDto.Configurations.LOTTERY_BULK_TIER2_BUY, "0")     // disabled
        stub(cs, ConfigDto.Configurations.LOTTERY_BULK_TIER2_BONUS, "10")
        stub(cs, ConfigDto.Configurations.LOTTERY_BULK_TIER3_BUY, "5")
        stub(cs, ConfigDto.Configurations.LOTTERY_BULK_TIER3_BONUS, "1")

        assertEquals(
            listOf(5L to 1L, 25L to 8L),
            LotteryHelper.bulkBonusTiers(cs, guildId),
        )
    }

    @Test
    fun `volumeMultiplierTiers clamps BP below identity up to identity`() {
        val cs = mockk<ConfigService>(relaxed = true)
        stub(cs, ConfigDto.Configurations.LOTTERY_MULT_TIER1_TOTAL, "10")
        stub(cs, ConfigDto.Configurations.LOTTERY_MULT_TIER1_BP, "5000")   // < identity, clamp up

        val tiers = LotteryHelper.volumeMultiplierTiers(cs, guildId)
        assertEquals(1, tiers.size)
        assertEquals(10L, tiers.first().first)
        assertEquals(LotteryHelper.MULTIPLIER_BP_IDENTITY, tiers.first().second)
    }

    @Test
    fun `volumeMultiplierTiers clamps BP above max down to max`() {
        val cs = mockk<ConfigService>(relaxed = true)
        stub(cs, ConfigDto.Configurations.LOTTERY_MULT_TIER1_TOTAL, "10")
        stub(cs, ConfigDto.Configurations.LOTTERY_MULT_TIER1_BP, "999999")

        val tiers = LotteryHelper.volumeMultiplierTiers(cs, guildId)
        assertEquals(LotteryHelper.MULTIPLIER_BP_MAX, tiers.first().second)
    }

    @Test
    fun `poolMilestones clamps pct to MILESTONE_PCT_MAX`() {
        val cs = mockk<ConfigService>(relaxed = true)
        stub(cs, ConfigDto.Configurations.LOTTERY_MILESTONE1_TICKETS, "100")
        stub(cs, ConfigDto.Configurations.LOTTERY_MILESTONE1_PCT, "999")

        val ms = LotteryHelper.poolMilestones(cs, guildId)
        assertEquals(LotteryHelper.MILESTONE_PCT_MAX.toLong(), ms.first().second)
    }
}
