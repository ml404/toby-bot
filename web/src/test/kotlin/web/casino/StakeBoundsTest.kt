package web.casino

import common.casino.dice.Dice
import database.dto.guild.ConfigDto
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [StakeBounds] and the `cfgLong` / `cfgLongMax` config
 * readers it leans on. The page UI must show the same effective bounds the
 * game service will enforce, so each game method has to read its own
 * min/max keys and the sentinel/coercion rules have to hold.
 */
class StakeBoundsTest {

    private lateinit var configService: ConfigService
    private lateinit var bounds: StakeBounds

    private val guildId = 4242L

    @BeforeEach
    fun setup() {
        configService = mockk()
        bounds = StakeBounds(configService)
        // Default: nothing configured. Individual tests stub specific keys.
        every { configService.getConfigByName(any(), any()) } returns null
    }

    private fun stub(key: Configurations, value: String?) {
        val dto = value?.let { ConfigDto(key.configValue, it, guildId.toString()) }
        every { configService.getConfigByName(key.configValue, guildId.toString()) } returns dto
    }

    /** Every public game accessor paired with the config keys it must read. */
    private data class Case(
        val name: String,
        val fn: (Long) -> Pair<Long, Long>,
        val min: Configurations,
        val max: Configurations,
    )

    private fun allCases(): List<Case> = listOf(
        Case("dice", bounds::dice, Configurations.DICE_MIN_STAKE, Configurations.DICE_MAX_STAKE),
        Case("coinflip", bounds::coinflip, Configurations.COINFLIP_MIN_STAKE, Configurations.COINFLIP_MAX_STAKE),
        Case("slots", bounds::slots, Configurations.SLOTS_MIN_STAKE, Configurations.SLOTS_MAX_STAKE),
        Case("highlow", bounds::highlow, Configurations.HIGHLOW_MIN_STAKE, Configurations.HIGHLOW_MAX_STAKE),
        Case("baccarat", bounds::baccarat, Configurations.BACCARAT_MIN_STAKE, Configurations.BACCARAT_MAX_STAKE),
        Case("keno", bounds::keno, Configurations.KENO_MIN_STAKE, Configurations.KENO_MAX_STAKE),
        Case("scratch", bounds::scratch, Configurations.SCRATCH_MIN_STAKE, Configurations.SCRATCH_MAX_STAKE),
        Case("roulette", bounds::roulette, Configurations.ROULETTE_MIN_STAKE, Configurations.ROULETTE_MAX_STAKE),
        Case("plinko", bounds::plinko, Configurations.PLINKO_MIN_STAKE, Configurations.PLINKO_MAX_STAKE),
        Case("horseRacing", bounds::horseRacing, Configurations.HORSE_RACING_MIN_STAKE, Configurations.HORSE_RACING_MAX_STAKE),
        Case("wheelOfFortune", bounds::wheelOfFortune, Configurations.WHEEL_OF_FORTUNE_MIN_STAKE, Configurations.WHEEL_OF_FORTUNE_MAX_STAKE),
        Case("blackjackSolo", bounds::blackjackSolo, Configurations.BLACKJACK_MIN_ANTE, Configurations.BLACKJACK_MAX_ANTE),
        Case("casinoHoldem", bounds::casinoHoldem, Configurations.HOLDEM_MIN_STAKE, Configurations.HOLDEM_MAX_STAKE),
        Case("duel", bounds::duel, Configurations.DUEL_MIN_STAKE, Configurations.DUEL_MAX_STAKE),
        Case("rps", bounds::rps, Configurations.RPS_MIN_STAKE, Configurations.RPS_MAX_STAKE),
        Case("ticTacToe", bounds::ticTacToe, Configurations.TICTACTOE_MIN_STAKE, Configurations.TICTACTOE_MAX_STAKE),
        Case("connect4", bounds::connect4, Configurations.CONNECT4_MIN_STAKE, Configurations.CONNECT4_MAX_STAKE),
    )

    @Test
    fun `every game accessor reads its own min and max config keys`() {
        for (case in allCases()) {
            stub(case.min, "50")
            stub(case.max, "500")

            val (min, max) = case.fn(guildId)

            assertEquals(50L, min, "${case.name} min")
            assertEquals(500L, max, "${case.name} max")
        }
    }

    @Test
    fun `missing config falls back to the game default bounds`() {
        // Nothing stubbed → getConfigByName returns null for every key.
        val (min, max) = bounds.dice(guildId)
        assertEquals(Dice.MIN_STAKE, min)
        assertEquals(Dice.MAX_STAKE, max)
    }

    @Test
    fun `min is coerced to at least one`() {
        stub(Configurations.DICE_MIN_STAKE, "0")
        stub(Configurations.DICE_MAX_STAKE, "500")
        val (min, _) = bounds.dice(guildId)
        assertEquals(1L, min)
    }

    @Test
    fun `max stored as zero means no upper cap`() {
        stub(Configurations.DICE_MIN_STAKE, "10")
        stub(Configurations.DICE_MAX_STAKE, "0")
        val (_, max) = bounds.dice(guildId)
        assertEquals(Long.MAX_VALUE, max)
    }

    @Test
    fun `max below min is coerced up to min`() {
        stub(Configurations.DICE_MIN_STAKE, "100")
        stub(Configurations.DICE_MAX_STAKE, "50")
        val (min, max) = bounds.dice(guildId)
        assertEquals(100L, min)
        assertEquals(100L, max)
    }

    @Test
    fun `unparseable values fall back to defaults`() {
        stub(Configurations.DICE_MIN_STAKE, "not-a-number")
        stub(Configurations.DICE_MAX_STAKE, "garbage")
        val (min, max) = bounds.dice(guildId)
        assertEquals(Dice.MIN_STAKE, min)
        assertEquals(Dice.MAX_STAKE, max)
    }
}
