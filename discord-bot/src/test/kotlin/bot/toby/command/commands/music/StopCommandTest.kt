package bot.toby.command.commands.music

import bot.toby.command.CommandContext
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.commands.music.player.StopCommand
import io.mockk.clearMocks
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color

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
        clearMocks(event, event.hook)
    }

    @Test
    fun test_callStopCommand_withBotAndUserBothInSameChannels() {
        // Arrange
        setUpAudioChannelsWithBotAndMemberInSameChannel()
        val commandContext = CommandContext(event)
        val hook = event.hook
        val embedSlot = slot<MessageEmbed>()

        // Act
        stopCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { hook.deleteOriginal() }
        verify(exactly = 1) { hook.sendMessageEmbeds(capture(embedSlot)) }

        // Assert on the captured EmbedBuilder
        val messageEmbed = embedSlot.captured
        assert(messageEmbed.title == "Player Stopped")
        assert(messageEmbed.description == "The player has been stopped and the queue has been cleared")
        assert(messageEmbed.color == Color.RED)
    }

    @Test
    fun test_callStopCommand_withBotNotInChannelAndUserInChannel() {
        // Arrange
        setUpAudioChannelsWithBotNotInChannel()
        val commandContext = CommandContext(event)

        // Act
        stopCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.hook.sendMessage("I need to be in a voice channel for this to work") }
    }

    @Test
    fun test_callStopCommand_withUserNotInChannelAndBotInChannel() {
        // Arrange
        setUpAudioChannelsWithUserNotInChannel()
        val commandContext = CommandContext(event)

        // Act
        stopCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.hook.sendMessage("You need to be in a voice channel for this command to work") }
    }

    @Test
    fun test_callStopCommand_withUserInChannelAndBotInChannel_ButChannelsAreDifferent() {
        // Arrange
        setUpAudioChannelsWithUserAndBotInDifferentChannels()
        val commandContext = CommandContext(event)

        // Act
        stopCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { event.hook.sendMessage("You need to be in the same voice channel as me for this to work") }
    }
}
