package bot.toby.handler

import core.managers.CommandManager
import database.service.leveling.XpAwardService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Guards the exception safety net added so a handler that throws after
 * deferReply() does not leave the user staring at "Bot is thinking…"
 * forever (which looks like the bot is offline).
 */
class SlashCommandEventListenerTest {

    private lateinit var commandManager: CommandManager
    private lateinit var xpAwardService: XpAwardService
    private lateinit var listener: SlashCommandEventListener

    @BeforeEach
    fun setup() {
        commandManager = mockk()
        xpAwardService = mockk(relaxed = true)
        // Dispatchers.Unconfined runs `launch {}` on the calling thread so
        // verifications fire after onSlashCommandInteraction returns.
        listener = SlashCommandEventListener(commandManager, xpAwardService, Dispatchers.Unconfined)
    }

    private fun event(acknowledged: Boolean): SlashCommandInteractionEvent {
        val guild: Guild = mockk(relaxed = true) {
            every { idLong } returns 7L
        }
        val member: Member = mockk(relaxed = true)
        val user: User = mockk(relaxed = true) {
            every { isBot } returns false
            every { idLong } returns 100L
        }
        val channel: MessageChannelUnion = mockk(relaxed = true) {
            every { idLong } returns 99L
        }
        val hook: InteractionHook = mockk(relaxed = true)
        val event: SlashCommandInteractionEvent = mockk(relaxed = true)
        every { event.guild } returns guild
        every { event.member } returns member
        every { event.user } returns user
        every { event.channel } returns channel
        every { event.hook } returns hook
        every { event.name } returns "boom"
        every { event.isAcknowledged } returns acknowledged
        return event
    }

    @Test
    fun `command throw after defer edits the deferred reply with an error`() {
        val event = event(acknowledged = true)
        val editAction: WebhookMessageEditAction<*> = mockk(relaxed = true)
        every { event.hook.editOriginal(any<String>()) } returns
            editAction as WebhookMessageEditAction<net.dv8tion.jda.api.entities.Message>
        every { commandManager.handle(event) } throws RuntimeException("boom")

        listener.onSlashCommandInteraction(event)

        verify(exactly = 1) {
            event.hook.editOriginal(match<String> { it.contains("Something went wrong") })
        }
    }

    @Test
    fun `command throw before defer falls back to ephemeral reply`() {
        val event = event(acknowledged = false)
        every { event.reply(any<String>()).setEphemeral(any()) } returns mockk(relaxed = true)
        every { commandManager.handle(event) } throws RuntimeException("boom")

        listener.onSlashCommandInteraction(event)

        verify(exactly = 1) {
            event.reply(match<String> { it.contains("Something went wrong") })
        }
    }

    @Test
    fun `happy path does not touch the error path`() {
        val event = event(acknowledged = true)
        every { commandManager.handle(event) } just Runs

        listener.onSlashCommandInteraction(event)

        verify(exactly = 0) { event.hook.editOriginal(any<String>()) }
        verify(exactly = 1) { commandManager.handle(event) }
    }
}
