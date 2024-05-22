package toby.command.commands.misc

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.emote.Emotes
import toby.jpa.dto.BrotherDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IBrotherService
import java.util.*

internal class BrotherCommandTest : CommandTest {
    @Mock
    private lateinit var brotherService: IBrotherService

    @Mock
    private var tobyEmote: Emoji? = null

    private lateinit var brotherCommand: BrotherCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        brotherService = Mockito.mock(IBrotherService::class.java)
        tobyEmote = Mockito.mock(RichCustomEmoji::class.java)
        brotherCommand = BrotherCommand(brotherService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        Mockito.reset(brotherService)
        Mockito.reset(tobyEmote)
    }

    @Test
    fun testDetermineBrother_BrotherExistsWithNoMention() {
        val mentions = Optional.empty<Mentions>()
        val user = Mockito.mock(UserDto::class.java)
        val brotherDto = BrotherDto()
        brotherDto.brotherName = "TestBrother"

        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("brother")).thenReturn(optionMapping)
        Mockito.`when`(CommandTest.event.user).thenReturn(
            Mockito.mock(
                User::class.java
            )
        )
        Mockito.`when`(brotherService.getBrotherById(user.discordId)).thenReturn(brotherDto)
        Mockito.`when`<Guild>(CommandTest.event.guild).thenReturn(CommandTest.guild)
        Mockito.`when`<RichCustomEmoji?>(CommandTest.jda.getEmojiById(Emotes.TOBY))
            .thenReturn(tobyEmote as RichCustomEmoji?)

        // Act
        brotherCommand.handle(CommandContext(CommandTest.event), UserDto(), 0)

        // Assert
        // Verify that the expected response is sent
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.anyString(), ArgumentMatchers.eq<String?>(brotherDto.brotherName))
    }

    @Test
    fun testDetermineBrother_BrotherDoesntExistWithNoMention() {
        // Arrange
        val mentions = Optional.empty<Mentions>()
        val userDto = Mockito.mock(UserDto::class.java)
        val user = Mockito.mock(User::class.java)
        val brotherDto = BrotherDto()
        brotherDto.brotherName = "TestBrother"

        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("brother")).thenReturn(optionMapping)
        Mockito.`when`(CommandTest.event.user).thenReturn(user)
        Mockito.`when`(user.name).thenReturn("userName")
        Mockito.`when`(brotherService.getBrotherById(userDto.discordId)).thenReturn(null)
        Mockito.`when`<Guild>(CommandTest.event.guild).thenReturn(CommandTest.guild)
        Mockito.`when`(CommandTest.guild.jda).thenReturn(CommandTest.jda)
        Mockito.`when`<RichCustomEmoji?>(CommandTest.jda.getEmojiById(Emotes.TOBY))
            .thenReturn(tobyEmote as RichCustomEmoji?)

        // Act
        brotherCommand.handle(CommandContext(CommandTest.event), UserDto(), 0)

        // Assert
        // Sends an angry message saying you're not my fucking brother with the toby emoji
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.eq<Emoji?>(tobyEmote)
        )
    }

    @Test
    fun testDetermineBrother_CalledByToby() {
        //Arrange
        val userDto = Mockito.mock(UserDto::class.java)
        val user = Mockito.mock(User::class.java)
        val brotherDto = BrotherDto()
        brotherDto.brotherName = "TestBrother"

        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`(CommandTest.event.user).thenReturn(user)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("brother")).thenReturn(optionMapping)
        Mockito.`when`(CommandTest.event.user).thenReturn(user)
        Mockito.`when`(user.idLong).thenReturn(BrotherCommand.tobyId)
        Mockito.`when`(brotherService.getBrotherById(userDto.discordId)).thenReturn(null)
        Mockito.`when`<Guild>(CommandTest.event.guild).thenReturn(CommandTest.guild)
        Mockito.`when`(CommandTest.guild.jda).thenReturn(CommandTest.jda)
        Mockito.`when`<RichCustomEmoji?>(CommandTest.jda.getEmojiById(Emotes.TOBY))
            .thenReturn(tobyEmote as RichCustomEmoji?)


        // Act
        brotherCommand.handle(CommandContext(CommandTest.event), UserDto(), 0)

        // Assert
        // Sends 'You're not my fucking brother Toby, you're me'
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.anyString(), ArgumentMatchers.eq<Emoji?>(tobyEmote))
    }
}
