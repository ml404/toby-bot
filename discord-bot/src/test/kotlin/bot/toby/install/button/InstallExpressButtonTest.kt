package bot.toby.install.button

import bot.toby.install.InstallCompletionService
import core.button.ButtonContext
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallExpressButtonTest {

    private lateinit var installCompletionService: InstallCompletionService
    private lateinit var button: InstallExpressButton
    private lateinit var ctx: ButtonContext
    private lateinit var event: ButtonInteractionEvent
    private lateinit var hook: InteractionHook
    private lateinit var member: Member
    private lateinit var guild: Guild
    private lateinit var editAction: WebhookMessageEditAction<Message>

    @BeforeEach
    fun setUp() {
        installCompletionService = mockk(relaxed = true)
        button = InstallExpressButton(installCompletionService)

        hook = mockk(relaxed = true)
        member = mockk(relaxed = true) { every { isOwner } returns true }
        guild = mockk(relaxed = true) { every { id } returns "g1" }

        event = mockk(relaxed = true) {
            every { this@mockk.member } returns this@InstallExpressButtonTest.member
            every { this@mockk.hook } returns this@InstallExpressButtonTest.hook
            every { deferEdit() } returns mockk(relaxed = true)
            every { reply(any<String>()) } returns mockk(relaxed = true) {
                every { setEphemeral(any()) } returns this
                every { queue() } just Runs
            }
        }
        ctx = mockk {
            every { this@mockk.event } returns this@InstallExpressButtonTest.event
            every { this@mockk.guild } returns this@InstallExpressButtonTest.guild
        }

        @Suppress("UNCHECKED_CAST")
        editAction = mockk<WebhookMessageEditAction<Message>>(relaxed = true)
        every { hook.editOriginalEmbeds(any<MessageEmbed>()) } returns editAction
        every { editAction.setComponents(*anyVararg<MessageTopLevelComponent>()) } returns editAction
        every { editAction.queue() } just Runs
    }

    @Test
    fun `defersReply is false`() {
        assertEquals(false, button.defersReply)
    }

    @Test
    fun `non-owner is rejected ephemerally with no completion or edits`() {
        every { member.isOwner } returns false

        button.handle(ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { event.reply(any<String>()) }
        verify(exactly = 0) { installCompletionService.complete(any(), any(), any()) }
        verify(exactly = 0) { hook.editOriginalEmbeds(any<MessageEmbed>()) }
        verify(exactly = 0) { event.deferEdit() }
    }

    @Test
    fun `owner happy path delegates completion in express mode`() {
        button.handle(ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { installCompletionService.complete(guild, "express", any()) }
    }

    @Test
    fun `owner happy path defers edit then shows done embed with stripped components`() {
        button.handle(ctx, mockk(relaxed = true), 0)

        verify(exactly = 1) { event.deferEdit() }
        verify(exactly = 1) { hook.editOriginalEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) {
            editAction.setComponents(*anyVararg<MessageTopLevelComponent>())
        }
        verify(exactly = 1) { editAction.queue() }
    }
}
