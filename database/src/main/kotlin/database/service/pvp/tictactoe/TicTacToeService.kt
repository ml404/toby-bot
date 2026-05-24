package database.service.pvp.tictactoe

import common.events.TicTacToeResolvedEvent
import database.boardgame.TurnBasedBoardWagerService
import database.dto.guild.ConfigDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import database.service.pvp.PvpWagerService

/**
 * Head-to-head Tic-Tac-Toe wager between two users. The wager
 * arithmetic + the three-terminal-case resolve routing live in
 * [TurnBasedBoardWagerService] — this class just plugs in the TTT-
 * specific config keys, xp-reason tag, and event factory.
 *
 * Publishes [TicTacToeResolvedEvent] on every winner-bearing match —
 * free play included — so achievements unlock regardless of whether
 * anyone bet. Draws never publish.
 */
@Service
class TicTacToeService @Autowired constructor(
    pvpWagerService: PvpWagerService,
    eventPublisher: ApplicationEventPublisher? = null,
) : TurnBasedBoardWagerService<TicTacToeResolvedEvent>(pvpWagerService, eventPublisher) {

    override val minStakeKey: ConfigDto.Configurations = ConfigDto.Configurations.TICTACTOE_MIN_STAKE
    override val maxStakeKey: ConfigDto.Configurations = ConfigDto.Configurations.TICTACTOE_MAX_STAKE
    override val xpReason: String = "tictactoe:win"

    override fun makeEvent(
        winnerDiscordId: Long,
        loserDiscordId: Long,
        guildId: Long,
        stake: Long,
        pot: Long,
    ): TicTacToeResolvedEvent = TicTacToeResolvedEvent(
        winnerDiscordId = winnerDiscordId,
        loserDiscordId = loserDiscordId,
        guildId = guildId,
        stake = stake,
        pot = pot,
    )
}
