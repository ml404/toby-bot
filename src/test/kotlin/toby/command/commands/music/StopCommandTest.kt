package toby.command.commands.music

import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest

internal class StopCommandTest : MusicCommandTest {
    lateinit var stopCommand: StopCommand

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        stopCommand = StopCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        clearMocks(CommandTest.event, CommandTest.interactionHook)
    }

    @Test
    fun test_callStopCommand_withBotAndUserBothInSameChannels() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)

        // Act
        stopCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage("The player has been stopped and the queue has been cleared") }
    }

    @Test
    fun test_callStopCommand_withBotNotInChannelAndUserInChannel() {
        // Arrange
        setUpAudioChannelsWithBotNotInChannel()
        val commandContext = CommandContext(CommandTest.event)

        // Act
        stopCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage("I need to be in a voice channel for this to work") }
    }

    @Test
    fun test_callStopCommand_withUserNotInChannelAndBotInChannel() {
        // Arrange
        setUpAudioChannelsWithUserNotInChannel()
        val commandContext = CommandContext(CommandTest.event)

        // Act
        stopCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage("You need to be in a voice channel for this command to work") }
    }

    @Test
    fun test_callStopCommand_withUserInChannelAndBotInChannel_ButChannelsAreDifferent() {
        // Arrange
        setUpAudioChannelsWithUserAndBotInDifferentChannels()
        val commandContext = CommandContext(CommandTest.event)

        // Act
        stopCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessage("You need to be in the same voice channel as me for this to work") }
    }
}
