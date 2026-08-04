package bot.toby.modal.modals

import bot.toby.helpers.IntroHelper
import bot.toby.helpers.PendingIntro
import core.modal.ModalContext
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The clip form behind **Apps → Set as my intro**.
 *
 * Before it existed the context menu saved straight from the message, which
 * made it the one entry point that couldn't trim — and, because the length
 * rule lives with the clip rules, the one that never checked how long the
 * source was. A ten-minute video became a ten-minute intro, reported as saved.
 */
class SetIntroFromMessageModalTest {

    private val discordId = 1L
    private val guildId = 7L

    private lateinit var introHelper: IntroHelper
    private lateinit var modal: SetIntroFromMessageModal
    private lateinit var event: ModalInteractionEvent
    private lateinit var ctx: ModalContext
    private lateinit var hook: InteractionHook
    private val replies = mutableListOf<String>()
    private val pending = mutableMapOf<Pair<Long, Long>, PendingIntro>()
    private lateinit var userDto: UserDto

    @BeforeEach
    fun setUp() {
        introHelper = mockk(relaxed = true)
        modal = SetIntroFromMessageModal(introHelper)
        userDto = UserDto(discordId = discordId, guildId = guildId)

        hook = mockk(relaxed = true)
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
        }
        val member = mockk<Member>(relaxed = true) { every { effectiveName } returns "Joiner" }
        event = mockk(relaxed = true) {
            every { this@mockk.hook } returns this@SetIntroFromMessageModalTest.hook
            every { user } returns mockk<User>(relaxed = true) {
                every { idLong } returns discordId
                every { effectiveName } returns "Joiner"
            }
        }
        ctx = mockk(relaxed = true) {
            every { this@mockk.event } returns this@SetIntroFromMessageModalTest.event
            every { this@mockk.guild } returns guild
            every { this@mockk.member } returns member
        }

        replies.clear()
        pending.clear()
        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(replies)) } returns send
        every { send.setEphemeral(true) } returns send

        every { introHelper.parkPendingIntro(any(), any(), any()) } answers {
            pending[firstArg<Long>() to secondArg<Long>()] = thirdArg()
        }
        every { introHelper.pendingIntro(any(), any()) } answers {
            pending[firstArg<Long>() to secondArg<Long>()]
        }
        every { introHelper.clearPendingIntro(any(), any()) } answers {
            pending.remove(firstArg<Long>() to secondArg<Long>())
        }
        every { introHelper.findUserById(discordId, guildId) } returns userDto
        // Pass the clip gate unless a test says otherwise.
        every { introHelper.validateClipAgainstSource(any(), any(), any(), any()) } answers {
            lastArg<(String?) -> Unit>().invoke(null)
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun field(value: String?): ModalMapping? =
        value?.let { mockk(relaxed = true) { every { asString } returns it } }

    private fun submit(volume: String? = null, start: String? = null, end: String? = null) {
        every { event.getValue(SetIntroFromMessageModal.FIELD_VOLUME) } returns field(volume)
        every { event.getValue(SetIntroFromMessageModal.FIELD_START) } returns field(start)
        every { event.getValue(SetIntroFromMessageModal.FIELD_END) } returns field(end)
        modal.handle(ctx, 5)
    }

    private fun parkUrl(url: String = "https://www.youtube.com/watch?v=abc", volume: Int = 80) {
        pending[guildId to discordId] = PendingIntro(attachment = null, url = url, volume = volume)
    }

    private fun parkFile(volume: Int = 80): Message.Attachment {
        val attachment = mockk<Message.Attachment>(relaxed = true)
        pending[guildId to discordId] = PendingIntro(attachment = attachment, url = null, volume = volume)
        return attachment
    }

    // --- the happy path -----------------------------------------------------

    @Test
    fun `submitting the form untouched saves at the guild default volume`() {
        parkUrl(volume = 80)

        submit()

        val uri = slot<URI>()
        verify { introHelper.handleUrl(event, userDto, "Joiner", 5, capture(uri), 80, null, null, null) }
        assertEquals("https://www.youtube.com/watch?v=abc", uri.captured.toString())
    }

    @Test
    fun `a clip typed into the form reaches the save`() {
        parkUrl()

        submit(volume = "45", start = "0:03", end = "0:12")

        verify { introHelper.handleUrl(event, userDto, "Joiner", 5, any(), 45, null, 3_000, 12_000) }
    }

    @Test
    fun `an attachment is saved through the attachment path`() {
        val attachment = parkFile()

        submit(volume = "60", start = "0:01", end = "0:06")

        verify { introHelper.handleAttachment(event, userDto, "Joiner", 5, attachment, 60, null, 1_000, 6_000) }
    }

    // --- validation ---------------------------------------------------------

    @Test
    fun `the source is put through the same length gate as slash setintro`() {
        // This is the bug the form exists to close: the context menu never ran
        // this check, so any length of video went straight in.
        parkUrl("https://www.youtube.com/watch?v=long")

        submit()

        verify { introHelper.validateClipAgainstSource("https://www.youtube.com/watch?v=long", null, null, any()) }
    }

    @Test
    fun `a source that fails the length gate is refused, not saved`() {
        parkUrl()
        every { introHelper.validateClipAgainstSource(any(), any(), any(), any()) } answers {
            lastArg<(String?) -> Unit>().invoke("Video is too long (600s). Max allowed is 15s")
        }

        submit()

        verify(exactly = 0) { introHelper.handleUrl(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("too long"), replies.single())
    }

    @Test
    fun `the clip the user typed is what gets validated`() {
        // A long video is fine once trimmed — which is the whole reason the
        // clip fields are on this form rather than being a later /editintro.
        parkUrl()

        submit(start = "1:00", end = "1:10")

        verify { introHelper.validateClipAgainstSource(any(), 60_000, 70_000, any()) }
    }

    @Test
    fun `an unparseable timestamp is reported without saving`() {
        parkUrl()

        submit(start = "banana")

        verify(exactly = 0) { introHelper.handleUrl(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("Clip start"), replies.single())
    }

    @Test
    fun `a volume outside the range is reported without saving`() {
        parkUrl()

        submit(volume = "500")

        verify(exactly = 0) { introHelper.handleUrl(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("between 0 and 100"), replies.single())
    }

    @Test
    fun `a non-numeric volume is reported without saving`() {
        parkUrl()

        submit(volume = "loud")

        verify(exactly = 0) { introHelper.handleUrl(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // --- state --------------------------------------------------------------

    @Test
    fun `a form with no source behind it says so rather than failing silently`() {
        submit()

        verify(exactly = 0) { introHelper.handleUrl(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(replies.single().contains("lost track"), replies.single())
    }

    @Test
    fun `at the slot limit the user is asked what to replace, clip and all`() {
        parkUrl()
        userDto.musicDtos = (1..3).map { MusicDto(userDto, it, "existing$it", 90, byteArrayOf(1)) }.toMutableList()
        val embedSend = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>()) } returns embedSend
        every { embedSend.addContent(any()) } returns embedSend
        every { embedSend.addComponents(any<net.dv8tion.jda.api.components.actionrow.ActionRow>()) } returns embedSend
        every { embedSend.setEphemeral(true) } returns embedSend

        submit(volume = "33", start = "0:02", end = "0:09")

        // Nothing saved yet — but the choice carries the clip forward, so the
        // replace menu finishes what the form started.
        verify(exactly = 0) { introHelper.handleUrl(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(33, pending[guildId to discordId]?.volume)
        assertEquals(2_000, pending[guildId to discordId]?.startMs)
        assertEquals(9_000, pending[guildId to discordId]?.endMs)
    }

    // --- form shape ---------------------------------------------------------

    @Test
    fun `the form pre-fills the volume so submitting unchanged is a no-op`() {
        val built = SetIntroFromMessageModal.buildFor("banger.mp3", 72)

        val volumeField = built.components
            .mapNotNull { (it as? Label)?.child as? TextInput }
            .single { it.customId == SetIntroFromMessageModal.FIELD_VOLUME }
        assertEquals("72", volumeField.value)
    }

    @Test
    fun `the clip fields start empty, matching the old one-click behaviour`() {
        val built = SetIntroFromMessageModal.buildFor("banger.mp3", 72)
        val fields = built.components
            .mapNotNull { (it as? Label)?.child as? TextInput }
            .associateBy { it.customId }

        assertEquals(null, fields[SetIntroFromMessageModal.FIELD_START]?.value)
        assertEquals(null, fields[SetIntroFromMessageModal.FIELD_END]?.value)
    }

    @Test
    fun `a long source name is trimmed to fit Discord's title cap`() {
        val title = SetIntroFromMessageModal.title("x".repeat(200))

        assertTrue(title.length <= 45, "title was ${title.length} chars: $title")
        assertTrue(title.endsWith("…"), title)
    }

    @Test
    fun `a blank source name still produces a usable title`() {
        assertEquals("Set as my intro", SetIntroFromMessageModal.title("   "))
    }

    @Test
    fun `the form fits Discord's five-component modal cap`() {
        assertTrue(SetIntroFromMessageModal.buildFor("a.mp3", 50).components.size <= 5)
    }
}
