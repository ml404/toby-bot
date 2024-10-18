package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.DefaultCommandContext
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class EightBallCommandTest : CommandTest {

    lateinit var command: EightBallCommand

    lateinit var userService: UserService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        userService = mockk()
        command = EightBallCommand(userService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testCommand_WithNotTom() {
        // Create a CommandContext
        val ctx = DefaultCommandContext(event)
        val deleteDelay = 0 // Set your desired deleteDelay

        // Test the handle method
        command.handle(ctx, CommandTest.requestingUserDto, deleteDelay)

        // Verify that the message was sent with the expected content
        verify { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun testCommand_WithTom() {
        // Create a CommandContext
        val ctx = DefaultCommandContext(event)

        // Mock requestingUserDto
        val tomsDiscordUser = database.dto.UserDto(
            EightBallCommand.TOMS_DISCORD_ID,
            1L,
            superUser = true,
            musicPermission = true,
            digPermission = true,
            memePermission = true,
            socialCredit = 0L,
            initiativeModifier = 0
        ) // You can set the user as needed
        val deleteDelay = 0 // Set your desired deleteDelay

        every { userService.updateUser(any()) } returns tomsDiscordUser

        // Test the handle method
        command.handle(ctx, tomsDiscordUser, deleteDelay)

        // Verify that the message was sent with the expected content
        verify {
            event.hook.sendMessage("MAGIC 8-BALL SAYS: Don't fucking talk to me.")
            event.hook.sendMessage(any<String>())
            userService.updateUser(any())
        }
    }
}
