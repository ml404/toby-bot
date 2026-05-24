package database.service

import common.events.pvp.connect4.Connect4ResolvedEvent
import database.boardgame.TurnBasedBoardWagerService
import database.dto.guild.ConfigDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import database.service.pvp.connect4.Connect4Service
import database.service.pvp.PvpWagerService

/**
 * Behavioural tests for the C4-specific knobs on [Connect4Service].
 *
 * The shared wager arithmetic + the three-terminal-case resolve
 * routing live in [TurnBasedBoardWagerService] and are exercised by
 * [database.boardgame.TurnBasedBoardWagerServiceTest]. This suite
 * only verifies that C4's specific config keys, xp-reason tag, and
 * event factory propagate correctly through the inherited
 * `resolveMatch`.
 */
class Connect4ServiceTest {

    private val guildId = 100L
    private val initiatorId = 1L
    private val opponentId = 2L

    private lateinit var pvp: PvpWagerService
    private lateinit var publisher: ApplicationEventPublisher
    private lateinit var service: Connect4Service

    @BeforeEach
    fun setUp() {
        pvp = mockk(relaxed = true)
        publisher = mockk(relaxed = true)
        service = Connect4Service(pvp, publisher)
    }

    @Test
    fun `startMatch uses the CONNECT4 config keys with the default bounds`() {
        every { pvp.readStakeBounds(any(), any(), any(), any(), any()) } returns (0L to 500L)
        every { pvp.preflightStart(any(), any(), any(), any(), any(), any()) } returns
            PvpWagerService.StartOutcome.Ok(initiatorBalance = 200L)

        service.startMatch(initiatorId, opponentId, guildId, 50L)

        verify(exactly = 1) {
            pvp.readStakeBounds(
                guildId,
                ConfigDto.Configurations.CONNECT4_MIN_STAKE,
                ConfigDto.Configurations.CONNECT4_MAX_STAKE,
                defaultMin = 0L,
                defaultMax = 500L,
            )
        }
    }

    @Test
    fun `payWinner is invoked with the connect4 xp-reason tag`() {
        every { pvp.payWinner(initiatorId, opponentId, 50L, guildId, "connect4:win", any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        service.resolveMatch(initiatorId, opponentId, guildId, stake = 50L, winnerDiscordId = initiatorId)

        verify(exactly = 1) {
            pvp.payWinner(initiatorId, opponentId, 50L, guildId, "connect4:win", any())
        }
    }

    @Test
    fun `resolveMatch publishes a Connect4ResolvedEvent on a clean win`() {
        every { pvp.payWinner(initiatorId, opponentId, 50L, guildId, any(), any()) } returns
            PvpWagerService.PayResult(150L, 100L, 100L, 0L, 10L)

        service.resolveMatch(initiatorId, opponentId, guildId, stake = 50L, winnerDiscordId = initiatorId)

        verify(exactly = 1) {
            publisher.publishEvent(
                match<Connect4ResolvedEvent> {
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
        assertEquals(initiatorId, (outcome as TurnBasedBoardWagerService.ResolveOutcome.Win).winnerDiscordId)
    }
}
