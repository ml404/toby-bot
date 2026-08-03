package bot.toby.modal.modals

import bot.toby.helpers.IntroHelper
import core.modal.ModalContext
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EditIntroModalTest {

    private lateinit var introHelper: IntroHelper
    private lateinit var modal: EditIntroModal
    private lateinit var ctx: ModalContext
    private lateinit var event: ModalInteractionEvent
    private lateinit var hook: InteractionHook
    private val replies = mutableListOf<String>()

    private val userDto = UserDto(discordId = 42L, guildId = 100L)

    private fun intro(
        index: Int = 1,
        name: String = "Original",
        volume: Int = 90,
        blob: ByteArray? = "https://www.youtube.com/watch?v=abc".toByteArray(),
        start: Int? = null,
        end: Int? = null,
    ) = MusicDto(userDto, index, name, volume, blob, start, end)

    @BeforeEach
    fun setUp() {
        introHelper = mockk(relaxed = true)
        modal = EditIntroModal(introHelper)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true)

        val guild = mockk<Guild>(relaxed = true) { every { idLong } returns 100L }
        val user = mockk<User> { every { idLong } returns 42L }
        every { event.user } returns user
        every { event.hook } returns hook
        every { event.modalId } returns EditIntroModal.customId("100_42_1")

        ctx = mockk(relaxed = true) {
            every { this@mockk.event } returns this@EditIntroModalTest.event
            every { this@mockk.guild } returns guild
        }

        replies.clear()
        val send = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(replies)) } returns send
        every { send.setEphemeral(true) } returns send
        every { send.queue() } just Runs

        // Callers manage their own intros unless a test says otherwise.
        every { introHelper.canManageIntro(any(), any(), any()) } returns true

        // By default the clip passes validation; individual tests override.
        every { introHelper.validateClipAgainstSource(any(), any(), any(), any()) } answers {
            lastArg<(String?) -> Unit>().invoke(null)
        }
    }

    private fun field(id: String, value: String?) {
        every { event.getValue(id) } returns value?.let { v -> mockk<ModalMapping> { every { asString } returns v } }
    }

    private fun fields(
        name: String? = null,
        volume: String? = null,
        start: String? = null,
        end: String? = null,
        enabled: String? = null,
    ) {
        field(EditIntroModal.FIELD_NAME, name)
        field(EditIntroModal.FIELD_VOLUME, volume)
        field(EditIntroModal.FIELD_START, start)
        field(EditIntroModal.FIELD_END, end)
        field(EditIntroModal.FIELD_ENABLED, enabled)
    }

    // --- buildFor -----------------------------------------------------------

    @Test
    fun `the form is pre-filled from the intro`() {
        val built = EditIntroModal.buildFor(intro(name = "My intro", volume = 55, start = 3_000, end = 9_000))

        assertEquals(EditIntroModal.customId("100_42_1"), built.id)
        assertEquals(5, built.components.size)
    }

    @Test
    fun `an unclipped intro opens with empty clip fields`() {
        // Nothing to assert beyond "it builds" — JDA rejects a null setValue,
        // which is exactly the regression this guards.
        val built = EditIntroModal.buildFor(intro())
        assertEquals(5, built.components.size)
    }

    // --- handle -------------------------------------------------------------

    @Test
    fun `submitting new values updates the intro`() {
        val existing = intro(name = "Original", volume = 90)
        every { introHelper.findIntroById("100_42_1") } returns existing
        fields(name = "Renamed", volume = "40", start = "0:03", end = "0:09")

        modal.handle(ctx, 0)

        val saved = slot<MusicDto>()
        verify { introHelper.updateIntro(capture(saved)) }
        assertEquals("Renamed", saved.captured.fileName)
        assertEquals(40, saved.captured.introVolume)
        assertEquals(3_000, saved.captured.startMs)
        assertEquals(9_000, saved.captured.endMs)
        assertTrue(replies.single().contains("Renamed"))
    }

    @Test
    fun `blank clip fields clear an existing clip`() {
        val existing = intro(start = 3_000, end = 9_000)
        every { introHelper.findIntroById("100_42_1") } returns existing
        fields(name = "Original", volume = "90", start = "", end = "")

        modal.handle(ctx, 0)

        val saved = slot<MusicDto>()
        verify { introHelper.updateIntro(capture(saved)) }
        assertNull(saved.captured.startMs)
        assertNull(saved.captured.endMs)
    }

    @Test
    fun `an untouched volume field keeps the current volume`() {
        val existing = intro(volume = 33)
        every { introHelper.findIntroById("100_42_1") } returns existing
        fields(name = "Original", volume = "")

        modal.handle(ctx, 0)

        val saved = slot<MusicDto>()
        verify { introHelper.updateIntro(capture(saved)) }
        assertEquals(33, saved.captured.introVolume)
    }

    @Test
    fun `out of range volume is rejected without saving`() {
        every { introHelper.findIntroById("100_42_1") } returns intro()
        fields(volume = "150")

        modal.handle(ctx, 0)

        verify(exactly = 0) { introHelper.updateIntro(any()) }
        assertTrue(replies.single().contains("between 0 and 100"))
    }

    @Test
    fun `unparseable timestamps are rejected without saving`() {
        every { introHelper.findIntroById("100_42_1") } returns intro()
        fields(start = "banana")

        modal.handle(ctx, 0)

        verify(exactly = 0) { introHelper.updateIntro(any()) }
        assertTrue(replies.single().startsWith("Clip start"))
    }

    @Test
    fun `an empty name is rejected without saving`() {
        every { introHelper.findIntroById("100_42_1") } returns intro()
        fields(name = "   ")

        modal.handle(ctx, 0)

        verify(exactly = 0) { introHelper.updateIntro(any()) }
        assertEquals("Name cannot be empty.", replies.single())
    }

    @Test
    fun `an over-long name is rejected without saving`() {
        every { introHelper.findIntroById("100_42_1") } returns intro()
        fields(name = "n".repeat(EditIntroModal.MAX_NAME_LENGTH + 1))

        modal.handle(ctx, 0)

        verify(exactly = 0) { introHelper.updateIntro(any()) }
        assertTrue(replies.single().contains("too long"))
    }

    @Test
    fun `a clip the shared rules reject is not saved`() {
        every { introHelper.findIntroById("100_42_1") } returns intro()
        fields(start = "0:00", end = "1:00")
        every { introHelper.validateClipAgainstSource(any(), any(), any(), any()) } answers {
            lastArg<(String?) -> Unit>().invoke("Clip is too long (60s). Max allowed is 15s.")
        }

        modal.handle(ctx, 0)

        verify(exactly = 0) { introHelper.updateIntro(any()) }
        assertEquals("Clip is too long (60s). Max allowed is 15s.", replies.single())
    }

    @Test
    fun `an intro belonging to another user is refused`() {
        every { event.modalId } returns EditIntroModal.customId("100_999_1")
        every { introHelper.canManageIntro("100_999_1", 100L, 42L) } returns false

        modal.handle(ctx, 0)

        verify(exactly = 0) { introHelper.updateIntro(any()) }
        assertEquals("That intro isn't yours to edit.", replies.single())
    }

    @Test
    fun `a super-user may edit someone else's intro`() {
        val theirs = MusicDto(UserDto(discordId = 999L, guildId = 100L), 1, "Theirs", 90, byteArrayOf(1))
        every { event.modalId } returns EditIntroModal.customId("100_999_1")
        every { introHelper.canManageIntro("100_999_1", 100L, 42L) } returns true
        every { introHelper.findIntroById("100_999_1") } returns theirs
        fields(volume = "10")

        modal.handle(ctx, 0)

        verify { introHelper.updateIntro(match<MusicDto> { it.introVolume == 10 }) }
    }

    @Test
    fun `a modal id with no intro reference is refused`() {
        every { event.modalId } returns EditIntroModal.MODAL_NAME

        modal.handle(ctx, 0)

        verify(exactly = 0) { introHelper.updateIntro(any()) }
        assertTrue(replies.single().contains("run `/editintro` again"))
    }

    @Test
    fun `an intro deleted between opening and submitting is reported`() {
        every { introHelper.findIntroById("100_42_1") } returns null
        fields()

        modal.handle(ctx, 0)

        verify(exactly = 0) { introHelper.updateIntro(any()) }
        assertTrue(replies.single().contains("may have been deleted"))
    }

    @Test
    fun `an uploaded file has no source duration to validate against`() {
        every { introHelper.findIntroById("100_42_1") } returns intro(blob = byteArrayOf(1, 2, 3))
        fields(start = "0:01", end = "0:05")

        modal.handle(ctx, 0)

        verify { introHelper.validateClipAgainstSource(null, 1_000, 5_000, any()) }
    }

    @Test
    fun `a url intro validates against its source`() {
        every { introHelper.findIntroById("100_42_1") } returns intro()
        fields(start = "0:01", end = "0:05")

        modal.handle(ctx, 0)

        verify { introHelper.validateClipAgainstSource("https://www.youtube.com/watch?v=abc", 1_000, 5_000, any()) }
    }

    // --- play-on-join switch ------------------------------------------------

    @Test
    fun `switching an intro off keeps it but takes it out of the rotation`() {
        val existing = intro()
        every { introHelper.findIntroById("100_42_1") } returns existing
        fields(enabled = "no")

        modal.handle(ctx, 0)

        val saved = slot<MusicDto>()
        verify { introHelper.updateIntro(capture(saved)) }
        assertFalse(saved.captured.enabled)
        // Everything else survives — this is a switch, not a delete.
        assertEquals("Original", saved.captured.fileName)
        assertEquals(90, saved.captured.introVolume)
    }

    @Test
    fun `switching an intro back on works`() {
        val existing = intro().apply { enabled = false }
        every { introHelper.findIntroById("100_42_1") } returns existing
        fields(enabled = "yes")

        modal.handle(ctx, 0)

        val saved = slot<MusicDto>()
        verify { introHelper.updateIntro(capture(saved)) }
        assertTrue(saved.captured.enabled)
    }

    @Test
    fun `the usual spellings of yes and no are accepted`() {
        listOf("yes", "y", "true", "on", "1", "ENABLED").forEach {
            assertEquals(true, EditIntroModal.parseEnabled(it), it)
        }
        listOf("no", "n", "false", "off", "0", "Disabled").forEach {
            assertEquals(false, EditIntroModal.parseEnabled(it), it)
        }
        assertNull(EditIntroModal.parseEnabled("maybe"))
    }

    @Test
    fun `an unrecognised value is rejected without saving`() {
        every { introHelper.findIntroById("100_42_1") } returns intro()
        fields(enabled = "maybe")

        modal.handle(ctx, 0)

        verify(exactly = 0) { introHelper.updateIntro(any()) }
        assertEquals("Play on join must be `yes` or `no`.", replies.single())
    }

    @Test
    fun `leaving the switch blank keeps the current setting`() {
        val existing = intro().apply { enabled = false }
        every { introHelper.findIntroById("100_42_1") } returns existing
        fields(enabled = "")

        modal.handle(ctx, 0)

        val saved = slot<MusicDto>()
        verify { introHelper.updateIntro(capture(saved)) }
        assertFalse(saved.captured.enabled)
    }
}
