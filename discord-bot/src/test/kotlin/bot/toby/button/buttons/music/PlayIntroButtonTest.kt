package bot.toby.button.buttons.music

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MusicPlayerHelper
import core.button.ButtonContext
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PlayIntroButtonTest {

    private val guildId = 7L
    private val targetId = 99L

    private lateinit var introHelper: IntroHelper
    private lateinit var button: PlayIntroButton
    private lateinit var ctx: ButtonContext
    private lateinit var event: ButtonInteractionEvent
    private lateinit var guild: Guild
    private lateinit var channel: AudioChannelUnion
    private lateinit var callerVoiceState: GuildVoiceState
    private lateinit var selfVoiceState: GuildVoiceState

    private val replies = mutableListOf<String>()
    private val caller = UserDto(discordId = 1L, guildId = guildId).apply { musicPermission = true }
    private val targetDto = UserDto(discordId = targetId, guildId = guildId)

    @BeforeEach
    fun setUp() {
        mockkObject(MusicPlayerHelper)
        introHelper = mockk(relaxed = true)
        button = PlayIntroButton(introHelper)

        channel = mockk(relaxed = true)
        callerVoiceState = mockk(relaxed = true) { every { this@mockk.channel } returns this@PlayIntroButtonTest.channel }
        selfVoiceState = mockk(relaxed = true) { every { this@mockk.channel } returns this@PlayIntroButtonTest.channel }
        val targetMember = mockk<Member>(relaxed = true) { every { effectiveName } returns "Them" }

        guild = mockk(relaxed = true) {
            every { idLong } returns guildId
            every { selfMember } returns mockk<SelfMember>(relaxed = true) {
                every { voiceState } returns selfVoiceState
            }
            every { getMemberById(targetId) } returns targetMember
        }
        val hook = mockk<InteractionHook>(relaxed = true)
        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        event = mockk(relaxed = true) {
            every { componentId } returns "playintro:$targetId"
            every { this@mockk.hook } returns hook
            every { user } returns mockk<User>(relaxed = true) { every { idLong } returns 1L }
        }
        ctx = mockk(relaxed = true) {
            every { this@mockk.event } returns this@PlayIntroButtonTest.event
            every { this@mockk.guild } returns this@PlayIntroButtonTest.guild
            every { member } returns mockk<Member>(relaxed = true) { every { voiceState } returns callerVoiceState }
        }

        replies.clear()
        every { hook.sendMessage(capture(replies)) } returns send
        every { send.setEphemeral(any()) } returns send

        targetDto.musicDtos = mutableListOf(MusicDto(targetDto, 1, "banger", 80, byteArrayOf(1)))
        every { introHelper.findUserById(targetId, guildId) } returns targetDto
        every { MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any()) } returns
            targetDto.musicDtos.first()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `it plays the intro of whoever the button was built for`() {
        button.handle(ctx, caller, 5)

        verify { MusicPlayerHelper.playUserIntro(targetDto, guild, any(), 5, any(), any(), any()) }
        assertTrue(replies.single().contains("banger"), replies.single())
    }

    @Test
    fun `the target comes from the component id, not the message`() {
        // The message is ephemeral and outlives nothing, so the id is the only
        // place the target can be carried.
        every { event.componentId } returns "playintro:12345"
        every { introHelper.findUserById(12345L, guildId) } returns targetDto

        button.handle(ctx, caller, 5)

        verify { introHelper.findUserById(12345L, guildId) }
    }

    @Test
    fun `a component id with no usable target is reported rather than throwing`() {
        every { event.componentId } returns "playintro:not-a-number"

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("lost track"), replies.single())
    }

    @Test
    fun `it still respects the music permission`() {
        // Whose intro it is was never the restricted part; making the bot play
        // something is.
        val noMusic = UserDto(discordId = 1L, guildId = guildId).apply { musicPermission = false }

        button.handle(ctx, noMusic, 5)

        verify(exactly = 0) { MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("permission"), replies.single())
    }

    @Test
    fun `it refuses when the bot is not in a voice channel`() {
        every { selfVoiceState.channel } returns null

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("I need to be in a voice channel"), replies.single())
    }

    @Test
    fun `it refuses when the caller is not in a voice channel`() {
        every { callerVoiceState.channel } returns null

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("You need to be in a voice channel"), replies.single())
    }

    @Test
    fun `it refuses when the caller is somewhere else`() {
        every { callerVoiceState.channel } returns mockk<AudioChannelUnion>(relaxed = true)

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("same voice channel"), replies.single())
    }

    @Test
    fun `a target with nothing playable is reported instead of played`() {
        targetDto.musicDtos = mutableListOf(
            MusicDto(targetDto, 1, "off", 80, byteArrayOf(1)).apply { enabled = false }
        )

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("nothing playable"), replies.single())
    }

    @Test
    fun `an intro that fails to load says so rather than replying with a success`() {
        every { MusicPlayerHelper.playUserIntro(any(), any(), any(), any(), any(), any(), any()) } returns null

        button.handle(ctx, caller, 5)

        assertTrue(replies.single().contains("Couldn't load"), replies.single())
    }

    @Test
    fun `the button label says whose intro it is`() {
        assertEquals("playintro:$targetId", PlayIntroButton.button(targetId, isSelf = false).customId)
        assertTrue(PlayIntroButton.button(targetId, isSelf = false).label.contains("theirs"))
        assertTrue(PlayIntroButton.button(1L, isSelf = true).label.contains("mine"))
    }
}
