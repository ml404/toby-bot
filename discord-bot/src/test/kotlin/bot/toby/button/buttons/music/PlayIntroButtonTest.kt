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
import net.dv8tion.jda.api.components.buttons.ButtonStyle
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
    private val ownerId = 99L

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
    private val owner = UserDto(discordId = ownerId, guildId = guildId)
    private lateinit var second: MusicDto

    @BeforeEach
    fun setUp() {
        mockkObject(MusicPlayerHelper)
        introHelper = mockk(relaxed = true)
        button = PlayIntroButton(introHelper)

        owner.musicDtos = mutableListOf(
            MusicDto(owner, 1, "first", 80, byteArrayOf(1)),
            MusicDto(owner, 2, "second", 80, byteArrayOf(2)),
            MusicDto(owner, 3, "third", 80, byteArrayOf(3)),
        )
        second = owner.musicDtos[1]

        channel = mockk(relaxed = true)
        callerVoiceState = mockk(relaxed = true) { every { this@mockk.channel } returns this@PlayIntroButtonTest.channel }
        selfVoiceState = mockk(relaxed = true) { every { this@mockk.channel } returns this@PlayIntroButtonTest.channel }
        val ownerMember = mockk<Member>(relaxed = true) { every { effectiveName } returns "Them" }

        guild = mockk(relaxed = true) {
            every { idLong } returns guildId
            every { selfMember } returns mockk<SelfMember>(relaxed = true) {
                every { voiceState } returns selfVoiceState
            }
            every { getMemberById(ownerId) } returns ownerMember
        }
        val hook = mockk<InteractionHook>(relaxed = true)
        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        event = mockk(relaxed = true) {
            every { componentId } returns "playintro:7_99_2"
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

        every { introHelper.findIntroById("7_99_2") } returns second
        every { MusicPlayerHelper.playIntro(any(), any(), any(), any(), any(), any(), any()) } returns second
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `it plays the intro the button names, not one of them at random`() {
        // The point of the change: the menu lists a member's intros by name,
        // so picking one and hearing a different one is a strange thing to do
        // to somebody. Three slots meant a one-in-three chance of the right
        // one before this.
        button.handle(ctx, caller, 5)

        verify { MusicPlayerHelper.playIntro(second, guild, any(), 5, any(), any(), any()) }
        assertTrue(replies.single().contains("second"), replies.single())
    }

    @Test
    fun `the confirmation names the intro, its clip and its slot`() {
        // Deliberately no owner name: that needs a member-cache lookup which
        // returns null for anyone not cached, and the reply is ephemeral and
        // attached to the menu they just opened.
        button.handle(ctx, caller, 5)

        assertTrue(replies.single().contains("second"), replies.single())
        assertTrue(replies.single().contains("#2"), replies.single())
    }

    @Test
    fun `the intro comes from the component id, since the message is ephemeral`() {
        every { event.componentId } returns "playintro:7_99_3"
        every { introHelper.findIntroById("7_99_3") } returns owner.musicDtos[2]

        button.handle(ctx, caller, 5)

        verify { introHelper.findIntroById("7_99_3") }
    }

    @Test
    fun `an id from another guild is refused rather than played`() {
        // The id arrives from the client, so having been offered it earlier
        // proves nothing about the one that came back.
        every { event.componentId } returns "playintro:12345_99_1"

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playIntro(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { introHelper.findIntroById(any()) }
        assertTrue(replies.single().contains("lost track"), replies.single())
    }

    @Test
    fun `a component id with no intro on it is reported rather than throwing`() {
        every { event.componentId } returns "playintro:"

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("lost track"), replies.single())
    }

    @Test
    fun `an intro deleted since the menu opened is reported, not played`() {
        every { introHelper.findIntroById("7_99_2") } returns null

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("has gone"), replies.single())
    }

    @Test
    fun `it still respects the music permission`() {
        // Whose intro it is was never the restricted part; making the bot play
        // something is.
        val noMusic = UserDto(discordId = 1L, guildId = guildId).apply { musicPermission = false }

        button.handle(ctx, noMusic, 5)

        verify(exactly = 0) { MusicPlayerHelper.playIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("permission"), replies.single())
    }

    @Test
    fun `it refuses when the bot is not in a voice channel`() {
        every { selfVoiceState.channel } returns null

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("I need to be in a voice channel"), replies.single())
    }

    @Test
    fun `it refuses when the caller is not in a voice channel`() {
        every { callerVoiceState.channel } returns null

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("You need to be in a voice channel"), replies.single())
    }

    @Test
    fun `it refuses when the caller is somewhere else`() {
        every { callerVoiceState.channel } returns mockk<AudioChannelUnion>(relaxed = true)

        button.handle(ctx, caller, 5)

        verify(exactly = 0) { MusicPlayerHelper.playIntro(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("same voice channel"), replies.single())
    }

    @Test
    fun `an intro that fails to load says so rather than replying with a success`() {
        every { MusicPlayerHelper.playIntro(any(), any(), any(), any(), any(), any(), any()) } returns null

        button.handle(ctx, caller, 5)

        assertTrue(replies.single().contains("Couldn't load"), replies.single())
    }

    // --- how the buttons are built -------------------------------------------

    @Test
    fun `each button carries its own intro's id and name`() {
        val built = PlayIntroButton.button(second)

        assertEquals("playintro:7_99_2", built.customId)
        assertTrue(built.label.contains("second"), built.label)
    }

    @Test
    fun `an intro that sits out the rotation is styled quietly rather than hidden`() {
        // Hearing a switched-off intro on purpose is exactly how you answer
        // "why doesn't this one play?", and the embed already labels it.
        val off = MusicDto(owner, 1, "muted", 80, byteArrayOf(1)).apply { enabled = false }
        val broken = MusicDto(owner, 2, "dead", 80, byteArrayOf(1)).apply { failureCount = 5 }

        assertEquals(ButtonStyle.SECONDARY, PlayIntroButton.button(off).style)
        assertEquals(ButtonStyle.SECONDARY, PlayIntroButton.button(broken).style)
        assertEquals(ButtonStyle.PRIMARY, PlayIntroButton.button(second).style)
    }

    @Test
    fun `a long track title is trimmed so three buttons still fit a row`() {
        val longName = MusicDto(owner, 1, "x".repeat(200), 80, byteArrayOf(1))

        val label = PlayIntroButton.button(longName).label

        assertTrue(label.length <= 30, "label was ${label.length} chars: $label")
        assertTrue(label.endsWith("…"), label)
    }
}
