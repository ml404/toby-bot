package bot.toby.command.commands.moderation

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.messageCreateAction
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import bot.toby.emote.Emotes
import io.mockk.*
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PollCommandTest : CommandTest {
    private lateinit var pollCommand: PollCommand
    private val tobyEmote: RichCustomEmoji = mockk()
    private val choicesOptionMapping: OptionMapping = mockk(relaxed = true)
    private val questionOptionMapping: OptionMapping = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        pollCommand = PollCommand()
        every {
            CommandTest.messageChannelUnion.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg())
        } returns messageCreateAction
        every { event.getOption("choices") } returns choicesOptionMapping
        every { event.getOption("question") } returns questionOptionMapping
        every { messageCreateAction.queue(any()) } just runs
    }

    @AfterEach
    fun teardown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun test_pollCommandWithChoices_sendsEmbed() {
        // Arrange
        val commandContext = DefaultCommandContext(event)

        every { event.getOption("choices") } returns choicesOptionMapping
        every { event.getOption("question") } returns null
        every { choicesOptionMapping.asString } returns "Choice1, Choice2"

        // Act
        pollCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessageEmbeds(any<MessageEmbed>())
        }
    }

    @Test
    fun test_pollCommandWithChoicesAndQuestion_sendsEmbed() {
        // Arrange
        val commandContext = DefaultCommandContext(event)
        every { choicesOptionMapping.asString } returns "Choice1, Choice2"
        every { questionOptionMapping.asString } returns "Question"

        // Act
        pollCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessageEmbeds(any<MessageEmbed>())
        }
    }

    @Test
    fun test_pollCommandWithoutChoices_sendsError() {
        // Arrange
        val commandContext = DefaultCommandContext(event)

        every { questionOptionMapping.asString } returns "Question"

        // Act
        pollCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 0) {
            CommandTest.messageChannelUnion.sendMessageEmbeds(any<MessageEmbed>())
        }
        verify(exactly = 1) {
            event.hook.sendMessage("Start a poll for every user in the server who has read permission in the channel you're posting to")
        }
    }

    @Test
    fun test_pollCommandWithTooManyChoices_sendsError() {
        // Arrange
        val commandContext = DefaultCommandContext(event)

        every { event.getOption("choices") } returns choicesOptionMapping
        every { choicesOptionMapping.asString } returns "Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1"
        every { questionOptionMapping.asString } returns "Question"
        every { CommandTest.jda.getEmojiById(Emotes.TOBY) } returns tobyEmote

        // Act
        pollCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 0) {
            CommandTest.messageChannelUnion.sendMessageEmbeds(any<MessageEmbed>())
        }
        verify(exactly = 1) {
            event.hook.sendMessageFormat("Please keep the poll size under 10 items, or else %s.", tobyEmote)
        }
    }
}
