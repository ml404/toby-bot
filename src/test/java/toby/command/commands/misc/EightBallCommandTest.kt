package toby.command.commands.misc

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.any
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

internal class EightBallCommandTest : CommandTest {

    private lateinit var command: EightBallCommand

    @Mock
    lateinit var userService: IUserService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        userService = Mockito.mock(IUserService::class.java)
        command = EightBallCommand(userService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testCommand_WithNotTom() {
        // Create a CommandContext
        val ctx = CommandContext(CommandTest.event)

        // Mock requestingUserDto
        val requestingUserDto = UserDto(1L, 1L,
            superUser = true,
            musicPermission = true,
            digPermission = true,
            memePermission = true,
            socialCredit = 0L,
            initiativeModifier = 0
        ) // You can set the user as needed
        val deleteDelay = 0 // Set your desired deleteDelay

        // Test the handle method
        command.handle(ctx, requestingUserDto, deleteDelay)

        // Verify that the message was sent with the expected content
        // You can use Mockito.verify() to check if event.getHook().sendMessage(...) was called with the expected message content.
        // For example:
        Mockito.verify(CommandTest.event.hook)
            .sendMessageFormat(ArgumentMatchers.eq("MAGIC 8-BALL SAYS: %s."), ArgumentMatchers.anyString())
    }

    @Test
    fun testCommand_WithTom() {
        // Create a CommandContext
        val ctx = CommandContext(CommandTest.event)

        // Mock requestingUserDto
        val requestingUserDto = UserDto(
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

        Mockito.`when`(userService.updateUser(any())).thenReturn(requestingUserDto)

        // Test the handle method
        command.handle(ctx, requestingUserDto, deleteDelay)

        // Verify that the message was sent with the expected content
        Mockito.verify(CommandTest.event.hook)
            .sendMessageFormat(ArgumentMatchers.eq("MAGIC 8-BALL SAYS: Don't fucking talk to me."))
        Mockito.verify(CommandTest.event.hook)
            .sendMessageFormat(ArgumentMatchers.eq("Deducted: %d social credit."), ArgumentMatchers.anyInt())
        Mockito.verify(userService, Mockito.times(1)).updateUser(any())
        // You can also verify that ICommand.deleteAfter was called with the expected arguments.
    }
}