package bot.toby.command.commands.music.intro

import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.targetMember
import bot.toby.command.commands.music.MusicCommandTest
import bot.toby.helpers.IntroHelper
import core.command.CommandContext
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ListIntrosCommandTest : MusicCommandTest {

    private lateinit var introHelper: IntroHelper
    private lateinit var command: ListIntrosCommand
    private lateinit var ctx: CommandContext

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        introHelper = mockk(relaxed = true)
        command = ListIntrosCommand(introHelper)
        ctx = mockk(relaxed = true)
        every { ctx.event } returns event
        every { event.getOption("user") } returns null
        every { member.effectiveAvatarUrl } returns "https://cdn.example/avatar.png"
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        unmockkAll()
    }

    @Test
    fun `lists the caller's own intros`() {
        val dto = UserDto(discordId = 1L, guildId = 1L)
        every { requestingUserDto.musicDtos } returns mutableListOf(
            MusicDto(dto, 2, "second", 40, "https://youtu.be/x".toByteArray(), 1_000, 5_000),
            MusicDto(dto, 1, "first", 90, byteArrayOf(1, 2, 3)),
        )

        command.handle(ctx, requestingUserDto, 5)

        val embeds = mutableListOf<MessageEmbed>()
        verify { event.hook.sendMessageEmbeds(capture(embeds)) }
        val fields = embeds.single().fields
        assertEquals(2, fields.size)
        assertTrue(fields[0].name!!.startsWith("#1 · first"))
        assertTrue(fields[1].name!!.startsWith("#2 · second"))
        assertTrue(fields[1].value!!.contains("0:01 – 0:05"))
    }

    @Test
    fun `renders an empty state rather than an error when nothing is set`() {
        every { requestingUserDto.musicDtos } returns mutableListOf()

        command.handle(ctx, requestingUserDto, 5)

        val embeds = mutableListOf<MessageEmbed>()
        verify { event.hook.sendMessageEmbeds(capture(embeds)) }
        assertTrue(embeds.single().description!!.contains("/setintro"))
    }

    @Test
    fun `a super-user can inspect another member`() {
        val other = UserDto(discordId = 99L, guildId = 1L).apply {
            musicDtos = mutableListOf(MusicDto(this, 1, "theirs", 90, byteArrayOf(1)))
        }
        every { requestingUserDto.superUser } returns true
        every { targetMember.idLong } returns 99L
        every { targetMember.effectiveAvatarUrl } returns "https://cdn.example/other.png"
        every { event.getOption("user") } returns mockk<OptionMapping> { every { asMember } returns targetMember }
        every { introHelper.findUserById(99L, 1L) } returns other

        command.handle(ctx, requestingUserDto, 5)

        val embeds = mutableListOf<MessageEmbed>()
        verify { event.hook.sendMessageEmbeds(capture(embeds)) }
        assertTrue(embeds.single().fields.single().name!!.contains("theirs"))
    }

    @Test
    fun `an ordinary member can inspect another member`() {
        // Viewing used to be super-user only, which protected nothing: an
        // intro announces itself out loud to the whole channel on every join.
        // All the restriction achieved was leaving "what was that thing you
        // had?" with no answer short of asking.
        val other = UserDto(discordId = 99L, guildId = 1L).apply {
            musicDtos = mutableListOf(MusicDto(this, 1, "theirs", 90, byteArrayOf(1)))
        }
        every { requestingUserDto.superUser } returns false
        every { targetMember.idLong } returns 99L
        every { targetMember.effectiveAvatarUrl } returns "https://cdn.example/other.png"
        every { event.getOption("user") } returns mockk<OptionMapping> { every { asMember } returns targetMember }
        every { introHelper.findUserById(99L, 1L) } returns other

        command.handle(ctx, requestingUserDto, 5)

        val embeds = mutableListOf<MessageEmbed>()
        verify { event.hook.sendMessageEmbeds(capture(embeds)) }
        assertTrue(embeds.single().fields.single().name!!.contains("theirs"))
        // `sendErrorMessage` names the server owner in its refusal, so never
        // reaching for it is the observable sign nothing was refused.
        verify(exactly = 0) { guild.owner }
    }

    @Test
    fun `passing yourself explicitly is not treated as a super-user action`() {
        every { requestingUserDto.superUser } returns false
        every { requestingUserDto.musicDtos } returns mutableListOf()
        every { event.getOption("user") } returns mockk<OptionMapping> { every { asMember } returns member }

        command.handle(ctx, requestingUserDto, 5)

        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
    }
}
