package database.service

import common.events.Connect4ResolvedEvent
import database.boardgame.TurnBasedBoardWagerService
import database.dto.ConfigDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/**
 * Head-to-head Connect 4 wager between two users. The wager
 * arithmetic + the three-terminal-case resolve routing live in
 * [TurnBasedBoardWagerService] — this class just plugs in the C4-
 * specific config keys, xp-reason tag, and event factory.
 *
 * Publishes [Connect4ResolvedEvent] on every winner-bearing match —
 * free play included — so achievements unlock regardless of whether
 * anyone bet. Draws never publish.
 */
@Service
class Connect4Service @Autowired constructor(
    pvpWagerService: PvpWagerService,
    eventPublisher: ApplicationEventPublisher? = null,
) : TurnBasedBoardWagerService<Connect4ResolvedEvent>(pvpWagerService, eventPublisher) {

    override val minStakeKey: ConfigDto.Configurations = ConfigDto.Configurations.CONNECT4_MIN_STAKE
    override val maxStakeKey: ConfigDto.Configurations = ConfigDto.Configurations.CONNECT4_MAX_STAKE
    override val xpReason: String = "connect4:win"

    override fun makeEvent(
        winnerDiscordId: Long,
        loserDiscordId: Long,
        guildId: Long,
        stake: Long,
        pot: Long,
    ): Connect4ResolvedEvent = Connect4ResolvedEvent(
        winnerDiscordId = winnerDiscordId,
        loserDiscordId = loserDiscordId,
        guildId = guildId,
        stake = stake,
        pot = pot,
    )
}
