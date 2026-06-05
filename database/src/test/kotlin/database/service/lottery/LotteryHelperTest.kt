package database.service.lottery

import database.dto.guild.ConfigDto
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [LotteryHelper] — the per-guild daily-lottery config
 * readers and the pure tier/milestone math. Mocks [ConfigService] so each
 * parse / default / coercion branch is exercised without a database.
 */
class LotteryHelperTest {

    private lateinit var config: ConfigService
    private val guildId = 7L

    @BeforeEach
    fun setup() {
        config = mockk()
        every { config.getConfigByName(any(), any()) } returns null
    }

    private fun stub(key: Configurations, value: String?) {
        val dto = value?.let { ConfigDto(key.configValue, it, guildId.toString()) }
        every { config.getConfigByName(key.configValue, guildId.toString()) } returns dto
    }

    // --- dailyEnabled ----------------------------------------------------

    @Test
    fun `dailyEnabled reads the truthy aliases`() {
        for (truthy in listOf("true", "1", "yes", "on", "ON", " Yes ")) {
            stub(Configurations.LOTTERY_DAILY_ENABLED, truthy)
            assertTrue(LotteryHelper.dailyEnabled(config, guildId), truthy)
        }
    }

    @Test
    fun `dailyEnabled defaults when unset and is false otherwise`() {
        assertEquals(LotteryHelper.DEFAULT_DAILY_ENABLED, LotteryHelper.dailyEnabled(config, guildId))
        stub(Configurations.LOTTERY_DAILY_ENABLED, "nope")
        assertEquals(false, LotteryHelper.dailyEnabled(config, guildId))
    }

    // --- numeric readers with defaults + coercion ------------------------

    @Test
    fun `dailyTicketPrice defaults, parses and clamps`() {
        assertEquals(LotteryHelper.DEFAULT_DAILY_TICKET_PRICE, LotteryHelper.dailyTicketPrice(config, guildId))
        stub(Configurations.LOTTERY_DAILY_TICKET_PRICE, "abc")
        assertEquals(LotteryHelper.DEFAULT_DAILY_TICKET_PRICE, LotteryHelper.dailyTicketPrice(config, guildId))
        stub(Configurations.LOTTERY_DAILY_TICKET_PRICE, "250")
        assertEquals(250L, LotteryHelper.dailyTicketPrice(config, guildId))
        stub(Configurations.LOTTERY_DAILY_TICKET_PRICE, "0")
        assertEquals(1L, LotteryHelper.dailyTicketPrice(config, guildId))
        stub(Configurations.LOTTERY_DAILY_TICKET_PRICE, "999999999")
        assertEquals(LotteryHelper.MAX_DAILY_TICKET_PRICE, LotteryHelper.dailyTicketPrice(config, guildId))
    }

    @Test
    fun `dailySeedPct clamps into 1 to 100`() {
        assertEquals(LotteryHelper.DEFAULT_DAILY_SEED_PCT, LotteryHelper.dailySeedPct(config, guildId))
        stub(Configurations.LOTTERY_DAILY_SEED_PCT, "0")
        assertEquals(1L, LotteryHelper.dailySeedPct(config, guildId))
        stub(Configurations.LOTTERY_DAILY_SEED_PCT, "500")
        assertEquals(100L, LotteryHelper.dailySeedPct(config, guildId))
    }

    @Test
    fun `dailyRevenueJackpotPct clamps into 0 to 100`() {
        assertEquals(LotteryHelper.DEFAULT_DAILY_REVENUE_JACKPOT_PCT, LotteryHelper.dailyRevenueJackpotPct(config, guildId))
        stub(Configurations.LOTTERY_DAILY_REVENUE_JACKPOT_PCT, "-5")
        assertEquals(0L, LotteryHelper.dailyRevenueJackpotPct(config, guildId))
        stub(Configurations.LOTTERY_DAILY_REVENUE_JACKPOT_PCT, "60")
        assertEquals(60L, LotteryHelper.dailyRevenueJackpotPct(config, guildId))
    }

    @Test
    fun `dailyMinBuyers defaults and clamps into 1 to 50`() {
        assertEquals(LotteryHelper.DEFAULT_DAILY_MIN_BUYERS, LotteryHelper.dailyMinBuyers(config, guildId))
        stub(Configurations.LOTTERY_DAILY_MIN_BUYERS, "0")
        assertEquals(1, LotteryHelper.dailyMinBuyers(config, guildId))
        stub(Configurations.LOTTERY_DAILY_MIN_BUYERS, "100")
        assertEquals(LotteryHelper.MAX_DAILY_MIN_BUYERS, LotteryHelper.dailyMinBuyers(config, guildId))
    }

    // --- enum-ish readers ------------------------------------------------

    @Test
    fun `dailyMode accepts known modes and falls back otherwise`() {
        stub(Configurations.LOTTERY_DAILY_MODE, "weighted")
        assertEquals(LotteryHelper.MODE_WEIGHTED, LotteryHelper.dailyMode(config, guildId))
        stub(Configurations.LOTTERY_DAILY_MODE, "number_match")
        assertEquals(LotteryHelper.MODE_NUMBER_MATCH, LotteryHelper.dailyMode(config, guildId))
        stub(Configurations.LOTTERY_DAILY_MODE, "bogus")
        assertEquals(LotteryHelper.DEFAULT_DAILY_MODE, LotteryHelper.dailyMode(config, guildId))
    }

    @Test
    fun `lotteryPingMode accepts known modes and falls back otherwise`() {
        for (mode in listOf(LotteryHelper.PING_OFF, LotteryHelper.PING_HERE, LotteryHelper.PING_EVERYONE)) {
            stub(Configurations.LOTTERY_PING_MODE, mode.lowercase())
            assertEquals(mode, LotteryHelper.lotteryPingMode(config, guildId))
        }
        stub(Configurations.LOTTERY_PING_MODE, "loud")
        assertEquals(LotteryHelper.DEFAULT_PING_MODE, LotteryHelper.lotteryPingMode(config, guildId))
        // unset
        every { config.getConfigByName(Configurations.LOTTERY_PING_MODE.configValue, guildId.toString()) } returns null
        assertEquals(LotteryHelper.DEFAULT_PING_MODE, LotteryHelper.lotteryPingMode(config, guildId))
    }

    @Test
    fun `lotteryChannelId parses or returns null`() {
        assertNull(LotteryHelper.lotteryChannelId(config, guildId))
        stub(Configurations.LOTTERY_CHANNEL, "   ")
        assertNull(LotteryHelper.lotteryChannelId(config, guildId))
        stub(Configurations.LOTTERY_CHANNEL, "notanumber")
        assertNull(LotteryHelper.lotteryChannelId(config, guildId))
        stub(Configurations.LOTTERY_CHANNEL, " 12345 ")
        assertEquals(12345L, LotteryHelper.lotteryChannelId(config, guildId))
    }

    // --- tier readers ----------------------------------------------------

    @Test
    fun `bulkBonusTiers skips disabled tiers, clamps bonus and sorts`() {
        stub(Configurations.LOTTERY_BULK_TIER1_BUY, "20"); stub(Configurations.LOTTERY_BULK_TIER1_BONUS, "5")
        stub(Configurations.LOTTERY_BULK_TIER2_BUY, "0"); stub(Configurations.LOTTERY_BULK_TIER2_BONUS, "9") // disabled
        stub(Configurations.LOTTERY_BULK_TIER3_BUY, "10"); stub(Configurations.LOTTERY_BULK_TIER3_BONUS, "-3") // bonus clamped to 0
        val tiers = LotteryHelper.bulkBonusTiers(config, guildId)
        assertEquals(listOf(10L to 0L, 20L to 5L), tiers)
    }

    @Test
    fun `volumeMultiplierTiers clamps bp into identity to max and sorts`() {
        stub(Configurations.LOTTERY_MULT_TIER1_TOTAL, "100"); stub(Configurations.LOTTERY_MULT_TIER1_BP, "99999")
        stub(Configurations.LOTTERY_MULT_TIER2_TOTAL, "50"); stub(Configurations.LOTTERY_MULT_TIER2_BP, "5000")
        val tiers = LotteryHelper.volumeMultiplierTiers(config, guildId)
        assertEquals(
            listOf(50L to LotteryHelper.MULTIPLIER_BP_IDENTITY, 100L to LotteryHelper.MULTIPLIER_BP_MAX),
            tiers,
        )
    }

    @Test
    fun `poolMilestones clamps pct and sorts`() {
        stub(Configurations.LOTTERY_MILESTONE1_TICKETS, "500"); stub(Configurations.LOTTERY_MILESTONE1_PCT, "80")
        stub(Configurations.LOTTERY_MILESTONE2_TICKETS, "100"); stub(Configurations.LOTTERY_MILESTONE2_PCT, "10")
        val ms = LotteryHelper.poolMilestones(config, guildId)
        assertEquals(listOf(100L to 10L, 500L to LotteryHelper.MILESTONE_PCT_MAX.toLong()), ms)
    }

    @Test
    fun `tier readers return empty when nothing configured`() {
        assertTrue(LotteryHelper.bulkBonusTiers(config, guildId).isEmpty())
        assertTrue(LotteryHelper.volumeMultiplierTiers(config, guildId).isEmpty())
        assertTrue(LotteryHelper.poolMilestones(config, guildId).isEmpty())
    }

    // --- pure tier math --------------------------------------------------

    @Test
    fun `bulkBonusFor picks the highest matched tier`() {
        val tiers = listOf(10L to 1L, 50L to 6L, 100L to 15L)
        assertEquals(0L, LotteryHelper.bulkBonusFor(0, tiers))
        assertEquals(0L, LotteryHelper.bulkBonusFor(5, tiers))
        assertEquals(1L, LotteryHelper.bulkBonusFor(10, tiers))
        assertEquals(6L, LotteryHelper.bulkBonusFor(60, tiers))
        assertEquals(15L, LotteryHelper.bulkBonusFor(1000, tiers))
        assertEquals(0L, LotteryHelper.bulkBonusFor(50, emptyList()))
    }

    @Test
    fun `multiplierBpFor returns identity below the smallest tier`() {
        val tiers = listOf(10L to 11000, 50L to 20000)
        assertEquals(LotteryHelper.MULTIPLIER_BP_IDENTITY, LotteryHelper.multiplierBpFor(0, tiers))
        assertEquals(LotteryHelper.MULTIPLIER_BP_IDENTITY, LotteryHelper.multiplierBpFor(5, tiers))
        assertEquals(11000, LotteryHelper.multiplierBpFor(10, tiers))
        assertEquals(20000, LotteryHelper.multiplierBpFor(75, tiers))
        assertEquals(LotteryHelper.MULTIPLIER_BP_IDENTITY, LotteryHelper.multiplierBpFor(75, emptyList()))
    }

    @Test
    fun `milestonesBetween returns only newly-crossed unfired thresholds`() {
        val milestones = listOf(100L to 5L, 200L to 10L, 300L to 15L)
        assertTrue(LotteryHelper.milestonesBetween(200, 200, milestones, 0).isEmpty())
        assertEquals(
            listOf(200L to 10L, 300L to 15L),
            LotteryHelper.milestonesBetween(150, 300, milestones, alreadyFiredHighest = 100),
        )
        // already fired up to 300 → nothing new
        assertTrue(LotteryHelper.milestonesBetween(150, 350, milestones, alreadyFiredHighest = 300).isEmpty())
    }
}
