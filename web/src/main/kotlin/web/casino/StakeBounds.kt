package web.casino

import database.dto.guild.ConfigDto
import common.economy.Baccarat
import common.economy.Coinflip
import common.economy.Dice
import common.economy.Highlow
import common.economy.HorseRacing
import common.economy.Keno
import common.economy.Plinko
import common.economy.Roulette
import common.economy.ScratchCard
import common.economy.SlotMachine
import common.economy.WheelOfFortune
import common.blackjack.Blackjack
import common.poker.CasinoHoldem
import database.service.guild.ConfigService
import database.boardgame.TurnBasedBoardWagerService
import database.service.pvp.duel.DuelService
import database.service.pvp.rps.RpsService
import database.service.guild.cfgLong
import database.service.guild.cfgLongMax
import org.springframework.stereotype.Component

/**
 * Resolves per-guild min/max stake bounds for every minigame the web
 * surface renders. Mirrors the per-service config reads (e.g.
 * `DiceService.roll` reading `DICE_MIN_STAKE`/`DICE_MAX_STAKE`) so the
 * page UI shows the same effective bounds the service will enforce.
 *
 * Defaults fall through to each game's compile-time companion-object
 * constant so guilds that never touch the config see today's behaviour.
 */
@Component
class StakeBounds(
    private val configService: ConfigService,
) {
    fun dice(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.DICE_MIN_STAKE,
        ConfigDto.Configurations.DICE_MAX_STAKE,
        Dice.MIN_STAKE,
        Dice.MAX_STAKE,
    )

    fun coinflip(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.COINFLIP_MIN_STAKE,
        ConfigDto.Configurations.COINFLIP_MAX_STAKE,
        Coinflip.MIN_STAKE,
        Coinflip.MAX_STAKE,
    )

    fun slots(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.SLOTS_MIN_STAKE,
        ConfigDto.Configurations.SLOTS_MAX_STAKE,
        SlotMachine.MIN_STAKE,
        SlotMachine.MAX_STAKE,
    )

    fun highlow(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.HIGHLOW_MIN_STAKE,
        ConfigDto.Configurations.HIGHLOW_MAX_STAKE,
        Highlow.MIN_STAKE,
        Highlow.MAX_STAKE,
    )

    fun baccarat(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.BACCARAT_MIN_STAKE,
        ConfigDto.Configurations.BACCARAT_MAX_STAKE,
        Baccarat.MIN_STAKE,
        Baccarat.MAX_STAKE,
    )

    fun keno(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.KENO_MIN_STAKE,
        ConfigDto.Configurations.KENO_MAX_STAKE,
        Keno.MIN_STAKE,
        Keno.MAX_STAKE,
    )

    fun scratch(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.SCRATCH_MIN_STAKE,
        ConfigDto.Configurations.SCRATCH_MAX_STAKE,
        ScratchCard.MIN_STAKE,
        ScratchCard.MAX_STAKE,
    )

    fun roulette(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.ROULETTE_MIN_STAKE,
        ConfigDto.Configurations.ROULETTE_MAX_STAKE,
        Roulette.MIN_STAKE,
        Roulette.MAX_STAKE,
    )

    fun plinko(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.PLINKO_MIN_STAKE,
        ConfigDto.Configurations.PLINKO_MAX_STAKE,
        Plinko.MIN_STAKE,
        Plinko.MAX_STAKE,
    )

    fun horseRacing(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.HORSE_RACING_MIN_STAKE,
        ConfigDto.Configurations.HORSE_RACING_MAX_STAKE,
        HorseRacing.MIN_STAKE,
        HorseRacing.MAX_STAKE,
    )

    fun wheelOfFortune(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.WHEEL_OF_FORTUNE_MIN_STAKE,
        ConfigDto.Configurations.WHEEL_OF_FORTUNE_MAX_STAKE,
        WheelOfFortune.MIN_STAKE,
        WheelOfFortune.MAX_STAKE,
    )

    fun blackjackSolo(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.BLACKJACK_MIN_ANTE,
        ConfigDto.Configurations.BLACKJACK_MAX_ANTE,
        Blackjack.MULTI_MIN_ANTE,
        Blackjack.MULTI_MAX_ANTE,
    )

    fun casinoHoldem(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.HOLDEM_MIN_STAKE,
        ConfigDto.Configurations.HOLDEM_MAX_STAKE,
        CasinoHoldem.MIN_STAKE,
        CasinoHoldem.MAX_STAKE,
    )

    fun duel(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.DUEL_MIN_STAKE,
        ConfigDto.Configurations.DUEL_MAX_STAKE,
        DuelService.MIN_STAKE,
        DuelService.MAX_STAKE,
    )

    fun rps(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.RPS_MIN_STAKE,
        ConfigDto.Configurations.RPS_MAX_STAKE,
        RpsService.MIN_STAKE,
        RpsService.MAX_STAKE,
    )

    fun ticTacToe(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.TICTACTOE_MIN_STAKE,
        ConfigDto.Configurations.TICTACTOE_MAX_STAKE,
        TurnBasedBoardWagerService.DEFAULT_MIN_STAKE,
        TurnBasedBoardWagerService.DEFAULT_MAX_STAKE,
    )

    fun connect4(guildId: Long): Pair<Long, Long> = read(
        guildId,
        ConfigDto.Configurations.CONNECT4_MIN_STAKE,
        ConfigDto.Configurations.CONNECT4_MAX_STAKE,
        TurnBasedBoardWagerService.DEFAULT_MIN_STAKE,
        TurnBasedBoardWagerService.DEFAULT_MAX_STAKE,
    )

    private fun read(
        guildId: Long,
        minKey: ConfigDto.Configurations,
        maxKey: ConfigDto.Configurations,
        defaultMin: Long,
        defaultMax: Long,
    ): Pair<Long, Long> {
        val min = configService.cfgLong(minKey, guildId, default = defaultMin, min = 1L)
        // Max uses cfgLongMax — stored "0" means "no upper cap" → Long.MAX_VALUE
        val max = configService.cfgLongMax(maxKey, guildId, default = defaultMax, min = min)
        return min to max
    }
}
