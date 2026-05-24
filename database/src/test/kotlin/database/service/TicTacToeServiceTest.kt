package database.service

import common.events.pvp.tictactoe.TicTacToeResolvedEvent
import database.boardgame.TurnBasedBoardWagerService
import database.dto.guild.ConfigDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import database.service.pvp.PvpWagerService
import database.service.pvp.tictactoe.TicTacToeService

/**
 * Behavioural tests for the TTT-specific knobs on [TicTacToeService].
 *
 * The shared wager arithmetic + the three-terminal-case resolve
 * routing live in [TurnBasedBoardWagerService] and are exercised by
 * [database.boardgame.TurnBasedBoardWagerServiceTest]. This suite
 * only verifies that TTT's specific config keys, xp-reason tag, and
 * event factory propagate correctly through the inherited
 * `resolveMatch`.
 */
class TicTacToeServiceTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    private lateinit var pvp: PvpWagerService
    private lateinit var publisher: ApplicationEventPublisher
    private lateinit var service: TicTacToeService

    @BeforeEach
    fun setUp() {
        pvp = mockk(relaxed = true)
        publisher = mockk(relaxed = true)
        service = TicTacToeService(pvp, publisher)
    }

    @Test
    fun `startMatch uses the TICTACTOE config keys with the TTT defaults`() {
        every { pvp.readStakeBounds(any(), any(), any(), any(), any()) } returns (0L to 500L)
        every { pvp.preflightStart(any(), any(), any(), any(), any(), any()) } returns
            PvpWagerService.StartOutcome.Ok(initiatorBalance = 200L)

        service.startMatch(initiatorId, opponentId, guildId, 50L)

        verify(exactly = 1) {
            pvp.readStakeBounds(
                guildId,
                ConfigDto.Configurations.TICTACTOE_MIN_STAKE,
                ConfigDto.Configurations.TICTACTOE_MAX_STAKE,
                defaultMin = 0L,
                defaultMax = 500L,
            )
        }
    }

    @Test
    fun `payWinner is invoked with the tictactoe xp-reason tag`() {
        every { pvp.payWinner(initiatorId, opponentId, 50L, guildId, "tictactoe:win", any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        service.resolveMatch(initiatorId, opponentId, guildId, stake = 50L, winnerDiscordId = initiatorId)

        verify(exactly = 1) {
            pvp.payWinner(initiatorId, opponentId, 50L, guildId, "tictactoe:win", any())
        }
    }

    @Test
    fun `resolveMatch publishes a TicTacToeResolvedEvent on a clean win`() {
        every { pvp.payWinner(initiatorId, opponentId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        service.resolveMatch(initiatorId, opponentId, guildId, stake = 50L, winnerDiscordId = initiatorId)

        verify(exactly = 1) {
            publisher.publishEvent(
                match<TicTacToeResolvedEvent> {
                    it.winnerDiscordId == initiatorId &&
                        it.loserDiscordId == opponentId &&
                        it.guildId == guildId &&
                        it.stake == 50L &&
                        it.pot == 100L
                }
            )
        }
    }

    @Test
    fun `resolveMatch propagates the shared Win shape from the base ResolveOutcome`() {
        every { pvp.payWinner(any(), any(), any(), any(), any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        val outcome = service.resolveMatch(initiatorId, opponentId, guildId, stake = 50L, winnerDiscordId = initiatorId)
        // The shared base now owns ResolveOutcome — this confirms the per-game
        // service returns the inherited Win variant, not a TTT-specific subtype.
        assertEquals(initiatorId, (outcome as TurnBasedBoardWagerService.ResolveOutcome.Win).winnerDiscordId)
    }
}
