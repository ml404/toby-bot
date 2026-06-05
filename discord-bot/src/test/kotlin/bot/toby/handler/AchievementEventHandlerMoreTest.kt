package bot.toby.handler

import bot.toby.notify.NotificationRouter
import common.events.pvp.connect4.Connect4ResolvedEvent
import common.events.pvp.rps.RpsResolvedEvent
import common.events.pvp.tictactoe.TicTacToeResolvedEvent
import common.notification.PushAdapter
import database.service.guild.AchievementService
import database.service.guild.ConfigService
import database.service.user.UserNotificationPrefService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Covers branches missed by [AchievementEventHandlerTest]:
 * - [AchievementEventHandler.onRpsResolved]   — winner unlock, win tiers, loser loss tiers
 * - [AchievementEventHandler.onTicTacToeResolved] — winner unlock, win tiers, loser loss tiers
 * - [AchievementEventHandler.onConnect4Resolved]  — winner unlock, win tiers, loser loss tiers
 * Cross-pollination guards (winner never gets loss credits, loser never
 * gets winner credits) are included for each game.
 */
class AchievementEventHandlerMoreTest {

    private val discordId = 100L
    private val guildId = 42L
    private val otherDiscordId = 200L

    private lateinit var achievementService: AchievementService
    private lateinit var router: NotificationRouter
    private lateinit var handler: AchievementEventHandler

    @BeforeEach
    fun setup() {
        achievementService = mockk(relaxed = true)
        val jda = mockk<JDA>(relaxed = true)
        val prefService = mockk<UserNotificationPrefService>(relaxed = true) {
            every { isOptedIn(any(), any(), any(), any()) } returns true
        }
        val configService = mockk<ConfigService>(relaxed = true)
        val pushAdapter = mockk<PushAdapter>(relaxed = true)
        router = spyk(NotificationRouter(jda, prefService, configService, pushAdapter))
        every { router.sendDm(any(), any(), any(), any()) } just runs
        every { router.sendPush(any(), any(), any(), any()) } just runs
        every { router.sendChannel(any(), any(), any(), any(), any(), any()) } just runs
        handler = AchievementEventHandler(achievementService, router)
    }

    // ---- RPS ----

    @Test
    fun `rps resolved unlocks first_rps_win for the winner`() {
        handler.onRpsResolved(
            RpsResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "first_rps_win")
        }
    }

    @Test
    fun `rps resolved progresses every rps_wins tier for the winner`() {
        handler.onRpsResolved(
            RpsResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        listOf(10, 25).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(discordId, guildId, "rps_wins_$tier", 1L)
            }
        }
    }

    @Test
    fun `rps resolved progresses every rps_losses tier for the loser`() {
        handler.onRpsResolved(
            RpsResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        listOf(5).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(otherDiscordId, guildId, "rps_losses_$tier", 1L)
            }
        }
    }

    @Test
    fun `rps winner never gets loss credits`() {
        handler.onRpsResolved(
            RpsResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        verify(exactly = 0) {
            achievementService.progress(discordId, guildId, match { it.startsWith("rps_losses_") }, any())
        }
    }

    @Test
    fun `rps loser never gets winner credits`() {
        handler.onRpsResolved(
            RpsResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        verify(exactly = 0) {
            achievementService.unlock(otherDiscordId, any(), any())
        }
        verify(exactly = 0) {
            achievementService.progress(otherDiscordId, guildId, match { it.startsWith("rps_wins_") }, any())
        }
    }

    // ---- TicTacToe ----

    @Test
    fun `tictactoe resolved unlocks first_tictactoe_win for the winner`() {
        handler.onTicTacToeResolved(
            TicTacToeResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "first_tictactoe_win")
        }
    }

    @Test
    fun `tictactoe resolved progresses every tictactoe_wins tier for the winner`() {
        handler.onTicTacToeResolved(
            TicTacToeResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        listOf(10, 25).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(discordId, guildId, "tictactoe_wins_$tier", 1L)
            }
        }
    }

    @Test
    fun `tictactoe resolved progresses every tictactoe_losses tier for the loser`() {
        handler.onTicTacToeResolved(
            TicTacToeResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        listOf(5).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(otherDiscordId, guildId, "tictactoe_losses_$tier", 1L)
            }
        }
    }

    @Test
    fun `tictactoe winner never gets loss credits`() {
        handler.onTicTacToeResolved(
            TicTacToeResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        verify(exactly = 0) {
            achievementService.progress(discordId, guildId, match { it.startsWith("tictactoe_losses_") }, any())
        }
    }

    @Test
    fun `tictactoe loser never gets winner credits`() {
        handler.onTicTacToeResolved(
            TicTacToeResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        verify(exactly = 0) {
            achievementService.unlock(otherDiscordId, any(), any())
        }
        verify(exactly = 0) {
            achievementService.progress(otherDiscordId, guildId, match { it.startsWith("tictactoe_wins_") }, any())
        }
    }

    // ---- Connect4 ----

    @Test
    fun `connect4 resolved unlocks first_connect4_win for the winner`() {
        handler.onConnect4Resolved(
            Connect4ResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "first_connect4_win")
        }
    }

    @Test
    fun `connect4 resolved progresses every connect4_wins tier for the winner`() {
        handler.onConnect4Resolved(
            Connect4ResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        listOf(10, 25).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(discordId, guildId, "connect4_wins_$tier", 1L)
            }
        }
    }

    @Test
    fun `connect4 resolved progresses every connect4_losses tier for the loser`() {
        handler.onConnect4Resolved(
            Connect4ResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        listOf(5).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(otherDiscordId, guildId, "connect4_losses_$tier", 1L)
            }
        }
    }

    @Test
    fun `connect4 winner never gets loss credits`() {
        handler.onConnect4Resolved(
            Connect4ResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        verify(exactly = 0) {
            achievementService.progress(discordId, guildId, match { it.startsWith("connect4_losses_") }, any())
        }
    }

    @Test
    fun `connect4 loser never gets winner credits`() {
        handler.onConnect4Resolved(
            Connect4ResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 0L, pot = 0L,
            )
        )
        verify(exactly = 0) {
            achievementService.unlock(otherDiscordId, any(), any())
        }
        verify(exactly = 0) {
            achievementService.progress(otherDiscordId, guildId, match { it.startsWith("connect4_wins_") }, any())
        }
    }
}
