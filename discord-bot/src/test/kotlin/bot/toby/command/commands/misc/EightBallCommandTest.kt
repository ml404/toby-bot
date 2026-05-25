package bot.toby.command.commands.misc

import bot.toby.command.DefaultCommandContext
import database.dto.user.UserDto
import database.service.user.UserService
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

internal class EightBallCommandTest {

    private lateinit var command: EightBallCommand
    private lateinit var userService: UserService

    private val event: SlashCommandInteractionEvent = mockk(relaxed = true)
    private val hook: InteractionHook = mockk(relaxed = true)
    private val user: User = mockk(relaxed = true)
    private val replyAction: ReplyCallbackAction = mockk(relaxed = true)

    @Suppress("UNCHECKED_CAST")
    private val editAction: WebhookMessageEditAction<Message> = mockk(relaxed = true)

    private val nonTomUser: UserDto = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        userService = mockk()
        command = EightBallCommand(userService)

        every { event.hook } returns hook
        every { event.user } returns user
        every { user.effectiveName } returns "Asker"
        every { event.deferReply() } returns replyAction
        every { replyAction.queue() } just Runs

        every { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) } returns editAction
        every { editAction.setComponents(any<ActionRow>()) } returns editAction
        every {
            editAction.setComponents(any<Collection<MessageTopLevelComponent>>())
        } returns editAction
        every { editAction.queue() } just Runs
        every { editAction.queue(any()) } just Runs

        every { nonTomUser.discordId } returns 1L
        every { nonTomUser.socialCredit } returns 0L
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `non-Tom invocation defers, edits with shake embed, then schedules a reveal edit with an Ask-again button`() {
        val ctx = DefaultCommandContext(event)

        command.handle(ctx, nonTomUser, deleteDelay = 0)

        // Defer is owned by DefaultCommandManager now.
        verify(exactly = 0) { event.deferReply() }
        // Shake edit fires immediately, reveal edit is queued after the delay —
        // both go through editOriginalEmbeds, so we expect 2 calls total.
        verify(exactly = 2) { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) }
        verify(exactly = 1) { editAction.setComponents(any<ActionRow>()) }
        verify(exactly = 1) {
            editAction.queueAfter(
                EightBallCommand.REVEAL_DELAY_MS,
                TimeUnit.MILLISECONDS,
                any<java.util.function.Consumer<in Message>>(),
            )
        }
    }

    @Test
    fun `Tom's invocation deducts social credit and renders the punishment embed`() {
        val ctx = DefaultCommandContext(event)
        val tom = UserDto(
            EightBallCommand.TOMS_DISCORD_ID,
            1L,
            superUser = true,
            musicPermission = true,
            digPermission = true,
            memePermission = true,
            socialCredit = 0L,
        )

        every { userService.updateUser(any()) } returns tom

        command.handle(ctx, tom, deleteDelay = 0)

        // Single embed edit (no shake/reveal staging for Tom).
        verify(exactly = 1) { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) }
        verify(exactly = 1) { userService.updateUser(any()) }
        // The deduction is -5 * choice where choice ∈ [1,20], so we just check
        // the sign + bounds rather than the exact value.
        val expectedRange = -100..-5
        assertTrue(
            tom.socialCredit!! in expectedRange,
            "expected social credit in $expectedRange, was ${tom.socialCredit}",
        )
    }

    @Test
    fun `RESPONSES list has exactly twenty entries to keep the choice mapping in range`() {
        assertEquals(20, EightBallCommand.RESPONSES.size)
    }

    @Test
    fun `ask routes through the supplied hook, not the event`() {
        // The button entry point calls `ask(hook, ...)` directly. This guards
        // against accidentally re-introducing a `ctx.event.hook` reference
        // inside `ask` that would only work for the slash command path.
        val standaloneHook: InteractionHook = mockk(relaxed = true)
        every { standaloneHook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) } returns editAction

        command.ask(standaloneHook, nonTomUser, "Tester", deleteDelay = 0)

        verify(atLeast = 1) { standaloneHook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) }
        // Crucially, the event's hook is never touched.
        verify(exactly = 0) { hook.editOriginalEmbeds(any<Collection<MessageEmbed>>()) }
    }
}
