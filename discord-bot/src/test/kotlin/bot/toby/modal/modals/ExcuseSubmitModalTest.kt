package bot.toby.modal.modals

import bot.toby.command.commands.misc.ExcuseCommand
import core.modal.ModalContext
import database.dto.social.ExcuseDto
import database.service.social.ExcuseService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.modals.ModalMapping
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExcuseSubmitModalTest {

    private lateinit var excuseService: ExcuseService
    private lateinit var modal: ExcuseSubmitModal
    private lateinit var ctx: ModalContext
    private lateinit var event: ModalInteractionEvent
    private lateinit var hook: InteractionHook
    private val messageSlot = slot<String>()

    @BeforeEach
    fun setup() {
        excuseService = mockk()
        modal = ExcuseSubmitModal(excuseService)
        hook = mockk(relaxed = true)
        event = mockk(relaxed = true)

        val guild = mockk<Guild> { every { idLong } returns 100L }
        val user = mockk<User> {
            every { idLong } returns 42L
            every { name } returns "matt"
        }
        val member = mockk<Member> { every { effectiveName } returns "Matt" }
        every { event.user } returns user
        every { event.member } returns member
        every { event.hook } returns hook

        ctx = mockk {
            every { this@mockk.event } returns this@ExcuseSubmitModalTest.event
            every { this@mockk.guild } returns guild
        }

        @Suppress("UNCHECKED_CAST")
        val sendAction = mockk<WebhookMessageCreateAction<Message>>(relaxed = true)
        every { hook.sendMessage(capture(messageSlot)) } returns sendAction
        every { sendAction.setEphemeral(true) } returns sendAction
        every { sendAction.queue() } just Runs
    }

    private fun submitWith(text: String?) {
        val mapping = text?.let { mockk<ModalMapping> { every { asString } returns it } }
        every { event.getValue(ExcuseSubmitModal.FIELD_TEXT) } returns mapping
    }

    @Test
    fun `name is excuse_submit`() {
        assertEquals("excuse_submit", modal.name)
    }

    @Test
    fun `blank submission is rejected and never reaches the service`() {
        submitWith("   ")

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("Provide some excuse text"))
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
        verify(exactly = 0) { excuseService.listAllGuildExcuses(any()) }
    }

    @Test
    fun `missing text field is rejected and never reaches the service`() {
        submitWith(null)

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("Provide some excuse text"))
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
    }

    @Test
    fun `duplicate excuse (case-insensitive) replies with EXISTING_EXCUSE_MESSAGE and does not create`() {
        // Duplicate detection is case-insensitive — same string with different
        // capitalisation must still bounce, otherwise the table fills with
        // near-duplicates.
        submitWith("I had to walk my dog")
        every { excuseService.listAllGuildExcuses(100L) } returns listOf(
            ExcuseDto(id = 1L, guildId = 100L, author = "x", excuse = "i HAD to walk my DOG", authorDiscordId = 9L),
        )

        modal.handle(ctx, 0)

        assertEquals(ExcuseCommand.EXISTING_EXCUSE_MESSAGE, messageSlot.captured)
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
    }

    @Test
    fun `unique excuse is persisted with trimmed text plus author identity and acknowledged with the saved id`() {
        submitWith("  Had to rescue a cat from a tree.  ")
        every { excuseService.listAllGuildExcuses(100L) } returns emptyList()
        val captured = slot<ExcuseDto>()
        every { excuseService.createNewExcuse(capture(captured)) } returns ExcuseDto(
            id = 123L, guildId = 100L, author = "Matt",
            excuse = "Had to rescue a cat from a tree.", authorDiscordId = 42L,
        )

        modal.handle(ctx, 0)

        assertEquals(100L, captured.captured.guildId)
        assertEquals("Matt", captured.captured.author)
        assertEquals("Had to rescue a cat from a tree.", captured.captured.excuse)
        assertEquals(42L, captured.captured.authorDiscordId)
        assertTrue(messageSlot.captured.contains("id '123'"))
        assertTrue(messageSlot.captured.contains("Had to rescue a cat from a tree."))
    }

    @Test
    fun `author falls back to user name when member is absent (DM submission)`() {
        submitWith("DM-side excuse")
        every { event.member } returns null
        every { excuseService.listAllGuildExcuses(100L) } returns emptyList()
        val captured = slot<ExcuseDto>()
        every { excuseService.createNewExcuse(capture(captured)) } returns ExcuseDto(
            id = 1L, guildId = 100L, author = "matt", excuse = "DM-side excuse", authorDiscordId = 42L,
        )

        modal.handle(ctx, 0)

        assertEquals("matt", captured.captured.author)
    }

    @Test
    fun `null entries returned by listAllGuildExcuses are filtered before equality check`() {
        // The service's signature returns List<ExcuseDto?> — there can be
        // nulls in the list. Without filterNotNull the equality check would
        // NPE on the first null and rebound as an internal error to the user.
        submitWith("brand new excuse")
        every { excuseService.listAllGuildExcuses(100L) } returns listOf(null, null)
        every { excuseService.createNewExcuse(any()) } returns ExcuseDto(
            id = 1L, guildId = 100L, author = "Matt", excuse = "brand new excuse", authorDiscordId = 42L,
        )

        modal.handle(ctx, 0)

        assertTrue(messageSlot.captured.contains("brand new excuse"))
    }
}
