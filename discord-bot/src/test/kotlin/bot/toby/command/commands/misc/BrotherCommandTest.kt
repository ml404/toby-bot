package bot.toby.command.commands.misc

import bot.toby.command.CommandContext
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.jda
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.user
import bot.toby.emote.Emotes
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BrotherCommandTest : CommandTest {
    lateinit var brotherService: database.service.IBrotherService
    lateinit var tobyEmote: RichCustomEmoji
    lateinit var brotherCommand: BrotherCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        brotherService = mockk()
        tobyEmote = mockk()
        brotherCommand = BrotherCommand(brotherService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearMocks(brotherService, tobyEmote)
    }

    @Test
    fun testDetermineBrother_BrotherExistsWithNoMention() {
        // Arrange
        val mentions = mockk<Mentions>()
        val brotherDto = database.dto.BrotherDto().apply {
            discordId = 1L
            brotherName = "TestBrother"
        }

        val optionMapping = mockk<OptionMapping>()

        every { event.getOption("brother") } returns optionMapping
        every { event.user.idLong } returns 1L
        every { user.idLong } returns 1L
        every { brotherService.getBrotherById(1) } returns brotherDto
        every { optionMapping.mentions } returns mentions
        every { mentions.members } returns emptyList()
        every { jda.getEmojiById(Emotes.TOBY) } returns tobyEmote

        // Act
        brotherCommand.handle(CommandContext(event), requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage(any<String>())
        }
    }

    @Test
    fun testDetermineBrother_BrotherDoesntExistWithNoMention() {
        // Arrange
        val mentions = mockk<Mentions>()
        val brotherDto = database.dto.BrotherDto().apply {
            discordId = 1
            brotherName = "TestBrother"
        }

        val optionMapping = mockk<OptionMapping>()

        every { event.getOption("brother") } returns optionMapping
        every { optionMapping.mentions } returns mentions
        every { mentions.members } returns emptyList()
        every { event.user } returns user
        every { brotherService.getBrotherById(1) } returns null
        every { event.guild } returns CommandTest.guild
        every { CommandTest.guild.jda } returns jda
        every { jda.getEmojiById(Emotes.TOBY) } returns tobyEmote

        // Act
        brotherCommand.handle(CommandContext(event), requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage(any<String>())
        }
    }

    @Test
    fun testDetermineBrother_CalledByToby() {
        // Arrange

        val optionMapping = mockk<OptionMapping>()

        every { event.getOption("brother") } returns optionMapping
        every { user.idLong } returns BrotherCommand.tobyId
        every { brotherService.getBrotherById(1) } returns null
        every { event.guild } returns CommandTest.guild
        every { CommandTest.guild.jda } returns jda
        every { jda.getEmojiById(Emotes.TOBY) } returns tobyEmote

        // Act
        brotherCommand.handle(CommandContext(event), requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage(any<String>())
        }
    }
}
