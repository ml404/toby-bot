package bot.toby.command.commands.music.intro

import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.interactionHook
import bot.toby.command.CommandTest.Companion.targetMember
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.commands.music.MusicCommandTest
import bot.toby.helpers.IntroHelper
import core.command.CommandContext
import database.dto.music.MusicDto
import database.dto.user.UserDto
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.entities.MessageEmbed
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EditIntroCommandTest : MusicCommandTest {

    private val introHelper: IntroHelper = mockk(relaxed = true)
    private val editIntroCommand = EditIntroCommand(introHelper)
    private lateinit var ctx: CommandContext

    @BeforeEach
    fun setup() {
        setupCommonMusicMocks()
        // The relaxed event would otherwise hand back a phantom member for
        // the user option and target it instead of the caller.
        every { event.getOption("user") } returns null
        ctx = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMusicMocks()
        unmockkAll()
    }

    @Test
    fun `test handle with no intros`() {
        val mockCtx: CommandContext = mockk(relaxed = true)
        every { mockCtx.event } returns event
        every { requestingUserDto.musicDtos } returns mutableListOf()

        // Call handle
        editIntroCommand.handle(mockCtx, requestingUserDto, 5)

        // Verify the reply indicating no intros
        verify { event.hook.sendMessage("You have no intros to edit. Add one with `/setintro`.") }
    }

    @Test
    fun testHandleWithIntros() {
        every { ctx.event } returns event

        val intro1 = MusicDto(requestingUserDto, 1, "Intro1", introVolume = 20)
        val intro2 = MusicDto(requestingUserDto, 2, "Intro2", introVolume = 20)

        every { requestingUserDto.musicDtos } returns mutableListOf(intro1, intro2)

        editIntroCommand.handle(ctx, requestingUserDto, 5)

        verify {
            event.hook.sendMessageEmbeds(
                match<MessageEmbed> { embed ->
                    embed.fields.map { it.name }.any { it!!.startsWith("#1 · Intro1") } &&
                        embed.fields.map { it.name }.any { it!!.startsWith("#2 · Intro2") }
                }
            )
        }
    }

    // --- super-user targeting ------------------------------------------------

    @Test
    fun `a super-user can edit someone else's intro`() {
        every { ctx.event } returns event
        val theirs = UserDto(discordId = 99L, guildId = 1L).apply {
            musicDtos = mutableListOf(MusicDto(this, 1, "theirs", 90, byteArrayOf(1)))
        }
        every { requestingUserDto.superUser } returns true
        every { targetMember.idLong } returns 99L
        every { targetMember.effectiveAvatarUrl } returns "https://cdn.example/other.png"
        every { event.getOption("user") } returns mockk<OptionMapping> { every { asMember } returns targetMember }
        every { introHelper.findUserById(99L, 1L) } returns theirs

        editIntroCommand.handle(ctx, requestingUserDto, 5)

        verify {
            event.hook.sendMessageEmbeds(match<MessageEmbed> { e -> e.fields.single().name!!.contains("theirs") })
        }
    }

    @Test
    fun `a non super-user cannot edit someone else's intro`() {
        every { ctx.event } returns event
        every { requestingUserDto.superUser } returns false
        every { targetMember.idLong } returns 99L
        every { event.getOption("user") } returns mockk<OptionMapping> { every { asMember } returns targetMember }

        editIntroCommand.handle(ctx, requestingUserDto, 5)

        verify(exactly = 0) { introHelper.findUserById(any(), any()) }
        verify(exactly = 0) { interactionHook.sendMessageEmbeds(any<MessageEmbed>()) }
        // sendErrorMessage names the server owner in its refusal.
        verify { guild.owner }
    }
}
