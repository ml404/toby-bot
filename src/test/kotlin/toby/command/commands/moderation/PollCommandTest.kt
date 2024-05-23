package toby.command.commands.moderation

import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.anyVararg
import toby.command.CommandContext
import toby.command.CommandTest
import toby.emote.Emotes

internal class PollCommandTest : CommandTest {
    private var pollCommand: PollCommand? = null

    @Mock
    private val tobyEmote: Emoji? = null

    @BeforeEach
    fun setup() {
        setUpCommonMocks()
        Mockito.doReturn(CommandTest.messageCreateAction)
            .`when`(CommandTest.messageChannelUnion)
            .sendMessageEmbeds(
                ArgumentMatchers.any(), anyVararg()

            )
        pollCommand = PollCommand()
    }

    @AfterEach
    fun teardown() {
        tearDownCommonMocks()
        Mockito.reset<MessageChannelUnion>(CommandTest.messageChannelUnion)
    }

    @Test
    fun test_pollCommandWithChoices_sendsEmbed() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val choicesOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("choices")).thenReturn(choicesOptionMapping)
        Mockito.`when`(choicesOptionMapping.asString).thenReturn("Choice1, Choice2")

        //Act
        pollCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify<MessageChannelUnion>(CommandTest.messageChannelUnion, Mockito.times(1))
            .sendMessageEmbeds(
                ArgumentMatchers.any(
                    MessageEmbed::class.java
                )
            )
    }

    @Test
    fun test_pollCommandWithChoicesAndQuestion_sendsEmbed() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val choicesOptionMapping = Mockito.mock(OptionMapping::class.java)
        val questionOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("choices")).thenReturn(choicesOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("question"))
            .thenReturn(questionOptionMapping)
        Mockito.`when`(choicesOptionMapping.asString).thenReturn("Choice1, Choice2")
        Mockito.`when`(questionOptionMapping.asString).thenReturn("Question")

        //Act
        pollCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify<MessageChannelUnion>(CommandTest.messageChannelUnion, Mockito.times(1))
            .sendMessageEmbeds(
                ArgumentMatchers.any(
                    MessageEmbed::class.java
                )
            )
    }

    @Test
    fun test_pollCommandWithoutChoices_sendsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)

        //Act
        pollCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify<MessageChannelUnion>(CommandTest.messageChannelUnion, Mockito.times(0))
            .sendMessageEmbeds(
                ArgumentMatchers.any(
                    MessageEmbed::class.java
                )
            )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("Start a poll for every user in the server who has read permission in the channel you're posting to"))
    }

    @Test
    fun test_pollCommandWithTooManyChoices_sendsError() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val choicesOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("choices")).thenReturn(choicesOptionMapping)
        Mockito.`when`(choicesOptionMapping.asString)
            .thenReturn("Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1, Choice2,Choice1")
        Mockito.`when`<RichCustomEmoji?>(CommandTest.jda.getEmojiById(Emotes.TOBY))
            .thenReturn(tobyEmote as RichCustomEmoji?)
        //Act
        pollCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify<MessageChannelUnion>(CommandTest.messageChannelUnion, Mockito.times(0))
            .sendMessageEmbeds(
                ArgumentMatchers.any(
                    MessageEmbed::class.java
                )
            )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Please keep the poll size under 10 items, or else %s."),
            ArgumentMatchers.eq<Emoji?>(tobyEmote)
        )
    }
}