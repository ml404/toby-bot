package bot.toby.button.buttons.economy

import bot.toby.modal.modals.TipMessageModal
import core.button.ButtonContext
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import net.dv8tion.jda.api.components.buttons.ButtonStyle
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.modals.Modal
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The paying half of the profile card.
 *
 * Everything after the form submits is the shared `TipMessageModal` path, so
 * what these cover is the handful of refusals that stop the form opening onto
 * a tip that can't land.
 */
class TipUserButtonTest {

    private val guildId = 7L
    private val callerId = 1L
    private val targetId = 99L

    private lateinit var button: TipUserButton
    private lateinit var ctx: ButtonContext
    private lateinit var event: ButtonInteractionEvent
    private lateinit var guild: Guild
    private lateinit var targetMember: Member

    private val modals = mutableListOf<Modal>()
    private val replies = mutableListOf<String>()
    private val caller = UserDto(discordId = callerId, guildId = guildId)

    @BeforeEach
    fun setUp() {
        button = TipUserButton()

        targetMember = mockk(relaxed = true) {
            every { idLong } returns targetId
            every { effectiveName } returns "Them"
            every { user } returns mockk<User>(relaxed = true) { every { isBot } returns false }
        }
        guild = mockk(relaxed = true) {
            every { idLong } returns guildId
            every { getMemberById(targetId) } returns targetMember
        }
        event = mockk(relaxed = true) {
            every { componentId } returns "tipuser:$targetId"
            every { user } returns mockk<User>(relaxed = true) { every { idLong } returns callerId }
        }
        ctx = mockk(relaxed = true) {
            every { this@mockk.event } returns this@TipUserButtonTest.event
            every { this@mockk.guild } returns this@TipUserButtonTest.guild
        }

        modals.clear()
        replies.clear()
        every { event.replyModal(capture(modals)) } returns mockk<ModalCallbackAction>(relaxed = true)
        every { event.reply(capture(replies)) } returns mockk<ReplyCallbackAction>(relaxed = true) {
            every { setEphemeral(any()) } returns this
        }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `it opens the tip form for the member the button names`() {
        button.handle(ctx, caller, 5)

        assertEquals(TipMessageModal.askAmountId(targetId), modals.single().id)
        assertTrue(modals.single().title.contains("Them"), modals.single().title)
    }

    @Test
    fun `the form has no message to point at, so it carries none`() {
        // Unlike the right-click entry: a profile card isn't "for" anything,
        // so an announcement from here has nothing to link back to.
        button.handle(ctx, caller, 5)

        assertEquals("${TipMessageModal.MODAL_NAME}:$targetId:", modals.single().id)
    }

    @Test
    fun `tipping yourself is refused before the form opens`() {
        every { event.componentId } returns "tipuser:$callerId"

        button.handle(ctx, caller, 5)

        assertTrue(modals.isEmpty())
        assertTrue(replies.single().contains("yourself"), replies.single())
    }

    @Test
    fun `tipping a bot is refused before the form opens`() {
        every { targetMember.user } returns mockk(relaxed = true) { every { isBot } returns true }

        button.handle(ctx, caller, 5)

        assertTrue(modals.isEmpty())
        assertTrue(replies.single().contains("bot"), replies.single())
    }

    @Test
    fun `a member who has left since the card was posted is reported`() {
        // The buttons outlive the message they sit on, and a public `/profile`
        // card can be pressed weeks later.
        every { guild.getMemberById(targetId) } returns null

        button.handle(ctx, caller, 5)

        assertTrue(modals.isEmpty())
        assertTrue(replies.single().contains("any more"), replies.single())
    }

    @Test
    fun `an id that arrives mangled is refused rather than guessed at`() {
        every { event.componentId } returns "tipuser:not-a-snowflake"

        button.handle(ctx, caller, 5)

        assertTrue(modals.isEmpty())
        assertTrue(replies.single().contains("lost track"), replies.single())
    }

    @Test
    fun `it does not defer, because a modal has to answer the interaction first`() {
        assertTrue(!button.defersReply)
    }

    @Test
    fun `the button carries the member it is for`() {
        val rendered = TipUserButton.button(targetMember)

        assertEquals("${TipUserButton.BUTTON_NAME}:$targetId", rendered.customId)
        assertEquals(ButtonStyle.SUCCESS, rendered.style)
    }
}
