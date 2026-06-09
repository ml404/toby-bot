package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import database.service.social.LoginStreakService
import database.service.social.LoginStreakService.ClaimResult
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class DailyCommandTest : CommandTest {

    private lateinit var loginStreakService: LoginStreakService
    private lateinit var command: DailyCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        loginStreakService = mockk(relaxed = true)
        command = DailyCommand(loginStreakService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun `claims the daily for the invoking user and replies`() {
        every { loginStreakService.claim(1L, 1L, any(), any()) } returns
            ClaimResult.Granted(
                currentStreak = 1,
                longestStreak = 1,
                xpGranted = 25L,
                creditsGranted = 50L,
                isNewBest = true,
            )

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify(exactly = 1) { loginStreakService.claim(1L, 1L, any(), any()) }
        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg<MessageEmbed>()) }
    }

    @Test
    fun `already-claimed result still replies without claiming again`() {
        every { loginStreakService.claim(any(), any(), any(), any()) } returns
            ClaimResult.AlreadyClaimed(currentStreak = 3, longestStreak = 5)

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify(exactly = 1) { loginStreakService.claim(any(), any(), any(), any()) }
        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg<MessageEmbed>()) }
    }

    @Test
    fun `outside a guild it does not claim`() {
        every { event.guild } returns null

        command.handle(DefaultCommandContext(event), requestingUserDto, 5)

        verify(exactly = 0) { loginStreakService.claim(any(), any(), any(), any()) }
    }
}
