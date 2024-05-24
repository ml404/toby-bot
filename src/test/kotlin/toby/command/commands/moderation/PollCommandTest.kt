package toby.command.commands.moderation

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.requestingUserDto
import toby.emote.Emotes

internal class PollCommandTest : CommandTest {
    private lateinit var pollCommand: PollCommand
    private val tobyEmote: RichCustomEmoji = mockk()

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        pollCommand = PollCommand()
        every {
            CommandTest.messageChannelUnion.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg())
        } returns CommandTest.messageCreateAction
    }

    @AfterEach
    fun teardown() {
        tearDownCommonMocks()
        clearMocks(CommandTest.messageChannelUnion, tobyEmote)
    }

    @Test
    fun test_pollCommandWithChoices_sendsEmbed() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val choicesOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption("choices") } returns choicesOptionMapping
        every { choicesOptionMapping.asString } returns "Choice1, Choice2"

        // Act
        pollCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            CommandTest.messageChannelUnion.sendMessageEmbeds(any<MessageEmbed>())
        }
    }

    @Test
    fun test_pollCommandWithChoicesAndQuestion_sendsEmbed() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val choicesOptionMapping = mockk<OptionMapping>()
        val questionOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption("choices") } returns choicesOptionMapping
        every { CommandTest.event.getOption("question") } returns questionOptionMapping
        every { choicesOptionMapping.asString } returns "Choice1, Choice2"
        every { questionOptionMapping.asString } returns "Question"

        // Act
        pollCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            CommandTest.messageChannelUnion.sendMessageEmbeds(any<MessageEmbed>())
        }
    }

    @Test
    fun test_pollCommandWithoutChoices_sendsError() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)

        // Act
        pollCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 0) {
            CommandTest.messageChannelUnion.sendMessageEmbeds(any<MessageEmbed>())
        }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessage("Start a poll for every user in the server who has read permission in the channel you're posting to")
        }
    }

    @Test
    fun test_pollCommandWithTooManyChoices_sendsError() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val choicesOptionMapping = mockk<OptionMapping>()

        every { CommandTest.event.getOption("choices") } returns choicesOptionMapping
        every { choicesOptionMapping.asString } returns "Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1"
        every { CommandTest.jda.getEmojiById(Emotes.TOBY) } returns tobyEmote

        // Act
        pollCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 0) {
            CommandTest.messageChannelUnion.sendMessageEmbeds(any<MessageEmbed>())
        }
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat("Please keep the poll size under 10 items, or else %s.", tobyEmote)
        }
    }
}
