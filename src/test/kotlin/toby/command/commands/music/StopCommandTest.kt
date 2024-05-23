package toby.command.commands.music

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest

internal class StopCommandTest : MusicCommandTest {
    var stopCommand: StopCommand? = null

    @BeforeEach
    fun setUp() {
        setupCommonMusicMocks()
        stopCommand = StopCommand()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
    }

    @Test
    fun test_callStopCommand_withBotAndUserBothInSameChannels() {
        //Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(CommandTest.event)

        //Act
        stopCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("The player has been stopped and the queue has been cleared"))
    }

    @Test
    fun test_callStopCommand_withBotNotInChannelAndUserInChannel() {
        //Arrange
        setUpAudioChannelsWithBotNotInChannel()
        val commandContext = CommandContext(CommandTest.event)

        //Act
        stopCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("I need to be in a voice channel for this to work"))
    }

    @Test
    fun test_callStopCommand_withUserNotInChannelAndBotInChannel() {
        //Arrange
        setUpAudioChannelsWithUserNotInChannel()
        val commandContext = CommandContext(CommandTest.event)

        //Act
        stopCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("You need to be in a voice channel for this command to work"))
    }

    @Test
    fun test_callStopCommand_withUserInChannelAndBotInChannel_ButChannelsAreDifferent() {
        //Arrange
        setUpAudioChannelsWithUserAndBotInDifferentChannels()
        val commandContext = CommandContext(CommandTest.event)

        //Act
        stopCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("You need to be in the same voice channel as me for this to work"))
    }
}