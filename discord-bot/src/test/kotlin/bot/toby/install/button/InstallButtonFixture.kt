package bot.toby.install.button

import core.button.ButtonContext
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction

/**
 * Shared mockk fixture for install-button tests. Each test class
 * instantiates this, configures owner state, optionally stubs the
 * source message's components for [InstallToggleButton] context
 * preservation, and runs the button under test against [ctx].
 */
internal class InstallButtonFixture(guildId: String = "g1") {
    val event: ButtonInteractionEvent = mockk(relaxed = true)
    val hook: InteractionHook = mockk(relaxed = true)
    val member: Member = mockk(relaxed = true)
    val guild: Guild = mockk(relaxed = true)
    val message: Message = mockk(relaxed = true)
    val ctx: ButtonContext

    @Suppress("UNCHECKED_CAST")
    val editAction: WebhookMessageEditAction<Message> =
        mockk<WebhookMessageEditAction<Message>>(relaxed = true)

    init {
        every { member.isOwner } returns true
        every { guild.id } returns guildId
        every { event.member } returns member
        every { event.hook } returns hook
        every { event.message } returns message
        every { event.deferEdit() } returns mockk(relaxed = true)
        every { event.reply(any<String>()) } returns mockk(relaxed = true) {
            every { setEphemeral(any()) } returns this
            every { queue() } just Runs
        }
        every { hook.editOriginalEmbeds(any<MessageEmbed>()) } returns editAction
        every { editAction.setComponents(*anyVararg<MessageTopLevelComponent>()) } returns editAction
        every {
            editAction.setComponents(any<Collection<MessageTopLevelComponent>>())
        } returns editAction
        every { editAction.queue() } just Runs

        ctx = mockk {
            every { this@mockk.event } returns this@InstallButtonFixture.event
            every { this@mockk.guild } returns this@InstallButtonFixture.guild
        }
    }

    fun asNonOwner() {
        every { member.isOwner } returns false
    }
}
