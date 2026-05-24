package bot.toby.notify

import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import common.notification.PushAdapter
import common.notification.PushPayload
import common.notification.Surface
import database.service.guild.ConfigService
import database.service.user.UserNotificationPrefService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Coverage for [NotificationRouter.dispatch] — the canonical fan-out
 * entry point that enforces "every supported surface for this kind has
 * a builder wired" at runtime.
 *
 * The bug class this guards against: shipping DM + channel for a kind
 * that supports push, forgetting to wire push, and discovering on a
 * customer report that opted-in users never received the notification.
 * After this refactor that mistake is a fast-failing exception during
 * dispatch (caught by the corresponding handler test) instead of a
 * silent production drop.
 *
 * Tests use a spied [NotificationRouter] so we can intercept the calls
 * the DSL forwards to the surface primitives without exercising the
 * JDA / push-adapter machinery (already covered by
 * [NotificationRouterDmTest], [NotificationRouterChannelTest], and
 * [NotificationRouterPushTest]).
 */
class NotificationRouterDispatchTest {

    private val guildId = 100L
    private val discordId = 200L

    private lateinit var jda: JDA
    private lateinit var prefService: UserNotificationPrefService
    private lateinit var configService: ConfigService
    private lateinit var pushAdapter: PushAdapter
    private lateinit var router: NotificationRouter

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        prefService = mockk(relaxed = true) {
            every { isOptedIn(any(), any(), any(), any()) } returns true
        }
        configService = mockk(relaxed = true)
        pushAdapter = mockk(relaxed = true)
        // Spy on a real router so we observe what the DSL forwards. The
        // surface primitives are still real methods — we mock dependencies
        // beneath them only as needed for each case.
        router = io.mockk.spyk(
            NotificationRouter(jda, prefService, configService, pushAdapter)
        )
        // Stub the surface primitives at the router level so we can
        // assert what dispatch forwards without dragging in JDA REST.
        every { router.sendDm(any(), any(), any(), any()) } just runs
        every { router.sendPush(any(), any(), any(), any()) } just runs
        every {
            router.sendChannel(any(), any(), any(), any(), any(), any())
        } just runs
    }

    // ---------- happy path ----------

    @Test
    fun `dispatch fans out to dm, push, and channel in one call for a kind that supports all three`() {
        // ACHIEVEMENT_UNLOCK supports all three surfaces.
        router.dispatch(NotificationChannelKind.ACHIEVEMENT_UNLOCK, discordId, guildId) {
            dm { msg("dm") }
            push { PushPayload("t", "b") }
            channel(route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT) { msg("ch") }
        }

        verify(exactly = 1) {
            router.sendDm(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, any())
        }
        verify(exactly = 1) {
            router.sendPush(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, any())
        }
        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT,
                originChannelId = null,
                message = any(),
                onSent = null,
                mentions = null,
            )
        }
    }

    @Test
    fun `dispatch forwards channel originChannelId, mentions, and onSent through to sendChannel`() {
        val mentions = ChannelMentions(
            kind = NotificationChannelKind.ACHIEVEMENT_UNLOCK,
            userIds = listOf(discordId),
        )
        val onSent: (net.dv8tion.jda.api.entities.Message) -> Unit = {}
        router.dispatch(NotificationChannelKind.ACHIEVEMENT_UNLOCK, discordId, guildId) {
            dm { msg("dm") }
            push { PushPayload("t", "b") }
            channel(
                route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT,
                originChannelId = 9999L,
                mentions = mentions,
                onSent = onSent,
            ) { msg("ch") }
        }

        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT,
                originChannelId = 9999L,
                message = any(),
                onSent = onSent,
                mentions = mentions,
            )
        }
    }

    // ---------- enforcement: missing surfaces fail fast ----------

    @Test
    fun `dispatch throws when a supported surface has no builder — the regression guard`() {
        // ACHIEVEMENT_UNLOCK supports DM + CHANNEL + PUSH. Forgetting push
        // is exactly the bug that shipped achievement push broken.
        val err = assertThrows(IllegalStateException::class.java) {
            router.dispatch(NotificationChannelKind.ACHIEVEMENT_UNLOCK, discordId, guildId) {
                dm { msg("dm") }
                channel(route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT) { msg("ch") }
                // push intentionally omitted
            }
        }
        assertNotNull(err.message)
        assert(err.message!!.contains("PUSH")) {
            "expected error to name the missing surface, got: ${err.message}"
        }
        // Nothing should have been forwarded — enforcement runs before fan-out.
        verify(exactly = 0) { router.sendDm(any(), any(), any(), any()) }
        verify(exactly = 0) { router.sendPush(any(), any(), any(), any()) }
        verify(exactly = 0) {
            router.sendChannel(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `dispatch reports every missing surface, not just the first`() {
        val err = assertThrows(IllegalStateException::class.java) {
            router.dispatch(NotificationChannelKind.ACHIEVEMENT_UNLOCK, discordId, guildId) {
                // Wire nothing.
            }
        }
        val msg = err.message ?: ""
        assert(msg.contains("DM") && msg.contains("CHANNEL") && msg.contains("PUSH")) {
            "expected all three surfaces in the error, got: $msg"
        }
    }

    // ---------- kinds with fewer supported surfaces ----------

    @Test
    fun `dispatch accepts a single dm builder for a dm-only kind (INTRO_PROMPT)`() {
        // INTRO_PROMPT supports DM only — push is not allowed for this
        // kind and channel isn't either. The DSL must succeed with just
        // dm{} and forward nothing else.
        router.dispatch(NotificationChannelKind.INTRO_PROMPT, discordId, guildId) {
            dm { msg("intro") }
        }
        verify(exactly = 1) {
            router.sendDm(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, any())
        }
        verify(exactly = 0) { router.sendPush(any(), any(), any(), any()) }
        verify(exactly = 0) {
            router.sendChannel(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `dispatch skips forwarding to surfaces the kind does not support, even when a builder is wired`() {
        // INTRO_PROMPT is DM-only. Wiring push{} is a caller-side typo;
        // the dispatch silently skips it rather than invoking sendPush
        // and relying on its inner short-circuit. The lazy payload
        // builder is also never invoked.
        var pushBuilderInvoked = false
        router.dispatch(NotificationChannelKind.INTRO_PROMPT, discordId, guildId) {
            dm { msg("intro") }
            push {
                pushBuilderInvoked = true
                PushPayload("t", "b")
            }
        }
        verify(exactly = 1) {
            router.sendDm(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, any())
        }
        verify(exactly = 0) { router.sendPush(any(), any(), any(), any()) }
        assert(!pushBuilderInvoked) { "push builder should not be invoked for an unsupported surface" }
    }

    @Test
    fun `dispatch requires both surfaces for a two-surface kind (STREAK_REMINDER = DM + PUSH)`() {
        // STREAK_REMINDER supports DM + PUSH but not CHANNEL.
        assertThrows(IllegalStateException::class.java) {
            router.dispatch(NotificationChannelKind.STREAK_REMINDER, discordId, guildId) {
                dm { msg("dm") } // push missing
            }
        }
        // And the happy path with both wired succeeds.
        router.dispatch(NotificationChannelKind.STREAK_REMINDER, discordId, guildId) {
            dm { msg("dm") }
            push { PushPayload("t", "b") }
        }
        verify(exactly = 1) {
            router.sendDm(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, any())
        }
        verify(exactly = 1) {
            router.sendPush(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, any())
        }
    }

    // ---------- DSL guards ----------

    @Test
    fun `dispatch rejects a duplicate dm builder so two listeners can't silently overwrite each other`() {
        assertThrows(IllegalStateException::class.java) {
            router.dispatch(NotificationChannelKind.ACHIEVEMENT_UNLOCK, discordId, guildId) {
                dm { msg("a") }
                dm { msg("b") }
                push { PushPayload("t", "b") }
                channel(route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT) { msg("ch") }
            }
        }
    }

    @Test
    fun `dispatch rejects a duplicate push builder`() {
        assertThrows(IllegalStateException::class.java) {
            router.dispatch(NotificationChannelKind.ACHIEVEMENT_UNLOCK, discordId, guildId) {
                dm { msg("dm") }
                push { PushPayload("t", "b") }
                push { PushPayload("t2", "b2") }
                channel(route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT) { msg("ch") }
            }
        }
    }

    @Test
    fun `dispatch rejects a duplicate channel builder`() {
        assertThrows(IllegalStateException::class.java) {
            router.dispatch(NotificationChannelKind.ACHIEVEMENT_UNLOCK, discordId, guildId) {
                dm { msg("dm") }
                push { PushPayload("t", "b") }
                channel(route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT) { msg("ch1") }
                channel(route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT) { msg("ch2") }
            }
        }
    }

    // ---------- enforcement covers every existing kind ----------

    @Test
    fun `every NotificationChannelKind declares at least one supported surface`() {
        // Sanity guard: an empty supportedSurfaces would make dispatch a
        // no-op that silently never fires — defeats the whole point.
        NotificationChannelKind.entries.forEach { kind ->
            assert(kind.supportedSurfaces.isNotEmpty()) {
                "$kind has no supported surfaces — dispatch would no-op."
            }
        }
    }

    @Test
    fun `Surface enum stays exhaustive — dispatch's when expression covers every entry`() {
        // If a fourth surface is added, dispatch's when{} won't compile.
        // This test pins today's set so the missing-surfaces detection
        // logic in dispatch is exhaustive by construction.
        assertEquals(
            setOf(Surface.DM, Surface.CHANNEL, Surface.PUSH),
            Surface.entries.toSet(),
        )
    }

    // ---------- multi-recipient dispatch ----------

    @Test
    fun `multi-recipient dispatch posts the channel broadcast exactly once regardless of recipient count`() {
        val winners = listOf(1L, 2L, 3L)
        router.dispatch(
            kind = NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
            discordIds = winners,
            guildId = guildId,
        ) {
            channel(route = ChannelRouteKey.LOTTERY) { msg("broadcast") }
            push { winnerId -> PushPayload("Won", "id=$winnerId") }
        }
        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.LOTTERY,
                originChannelId = null,
                message = any(),
                onSent = null,
                mentions = null,
            )
        }
    }

    @Test
    fun `multi-recipient dispatch fans push out to every recipient with the per-recipient builder`() {
        val winners = listOf(1L, 2L, 3L)
        router.dispatch(
            kind = NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
            discordIds = winners,
            guildId = guildId,
        ) {
            channel(route = ChannelRouteKey.LOTTERY) { msg("broadcast") }
            push { winnerId -> PushPayload("Won", "id=$winnerId") }
        }
        winners.forEach { winnerId ->
            verify(exactly = 1) {
                router.sendPush(
                    winnerId, guildId, NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET, any()
                )
            }
        }
    }

    @Test
    fun `multi-recipient push builder receives each recipient's discordId so payloads can personalise`() {
        // Capture each forwarded payload-builder and invoke it to verify
        // the body actually depends on the winner id.
        val winners = listOf(11L, 22L)
        val payloadBuildersByWinner = mutableMapOf<Long, () -> PushPayload>()
        every {
            router.sendPush(any(), any(), any(), any())
        } answers {
            @Suppress("UNCHECKED_CAST")
            payloadBuildersByWinner[firstArg()] = arg<() -> PushPayload>(3)
        }

        router.dispatch(
            kind = NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
            discordIds = winners,
            guildId = guildId,
        ) {
            channel(route = ChannelRouteKey.LOTTERY) { msg("broadcast") }
            push { winnerId -> PushPayload("Won", "id=$winnerId") }
        }

        assertEquals("id=11", payloadBuildersByWinner.getValue(11L).invoke().body)
        assertEquals("id=22", payloadBuildersByWinner.getValue(22L).invoke().body)
    }

    @Test
    fun `multi-recipient dispatch enforces every supported surface — forgetting push for a many-winners cycle fails fast`() {
        // LOTTERY_DRAW_WITH_MY_TICKET supports CHANNEL + PUSH. Wiring
        // only channel ships the broadcast but silently drops every
        // opted-in winner's personal notification — exactly the bug
        // class this DSL guards against.
        assertThrows(IllegalStateException::class.java) {
            router.dispatch(
                kind = NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
                discordIds = listOf(1L, 2L),
                guildId = guildId,
            ) {
                channel(route = ChannelRouteKey.LOTTERY) { msg("broadcast") }
            }
        }
    }

    @Test
    fun `multi-recipient dispatch handles an empty recipient list — channel broadcast still fires, push is a no-op`() {
        // No winners (e.g. lottery cycle with NoTickets). The channel
        // post still announces the open draw; push fan-out is zero.
        router.dispatch(
            kind = NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
            discordIds = emptyList(),
            guildId = guildId,
        ) {
            channel(route = ChannelRouteKey.LOTTERY) { msg("broadcast") }
            push { winnerId -> PushPayload("Won", "id=$winnerId") }
        }
        verify(exactly = 1) {
            router.sendChannel(any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { router.sendPush(any(), any(), any(), any()) }
    }

    @Test
    fun `multi-recipient dispatch fans DM out per recipient when the kind supports DM`() {
        // Hypothetical kind change: if LEVEL_UP were multi-recipient
        // (e.g. "guild milestone hit, congratulate every member who
        // contributed"), each gets a DM. Today no such caller exists,
        // but the API has to support it — pin the behaviour.
        router.dispatch(
            kind = NotificationChannelKind.LEVEL_UP, // supports DM + CHANNEL + PUSH
            discordIds = listOf(1L, 2L, 3L),
            guildId = guildId,
        ) {
            channel(route = ChannelRouteKey.LEVEL_UP) { msg("broadcast") }
            dm { id -> msg("dm-$id") }
            push { id -> PushPayload("Level Up", "for $id") }
        }
        verify(exactly = 1) { router.sendDm(1L, guildId, NotificationChannelKind.LEVEL_UP, any()) }
        verify(exactly = 1) { router.sendDm(2L, guildId, NotificationChannelKind.LEVEL_UP, any()) }
        verify(exactly = 1) { router.sendDm(3L, guildId, NotificationChannelKind.LEVEL_UP, any()) }
    }

    @Test
    fun `multi-recipient dispatch posts the channel broadcast BEFORE per-recipient pushes`() {
        // Ordering matters: the user should see the public shoutout
        // (or in-Discord context) before the push lands so a notification
        // tap that focuses the browser doesn't beat the channel post.
        val callOrder = mutableListOf<String>()
        every { router.sendChannel(any(), any(), any(), any(), any(), any()) } answers {
            callOrder += "channel"
        }
        every { router.sendPush(any(), any(), any(), any()) } answers {
            callOrder += "push"
        }
        router.dispatch(
            kind = NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
            discordIds = listOf(1L, 2L),
            guildId = guildId,
        ) {
            channel(route = ChannelRouteKey.LOTTERY) { msg("broadcast") }
            push { id -> PushPayload("Won", "$id") }
        }
        assertEquals(listOf("channel", "push", "push"), callOrder)
    }

    @Test
    fun `multi-recipient dispatch rejects duplicate dm builder`() {
        assertThrows(IllegalStateException::class.java) {
            router.dispatch(
                kind = NotificationChannelKind.LEVEL_UP,
                discordIds = listOf(1L),
                guildId = guildId,
            ) {
                channel(route = ChannelRouteKey.LEVEL_UP) { msg("ch") }
                dm { id -> msg("dm-$id") }
                dm { id -> msg("dm2-$id") }
                push { id -> PushPayload("t", "$id") }
            }
        }
    }

    @Test
    fun `multi-recipient dispatch rejects duplicate push builder`() {
        assertThrows(IllegalStateException::class.java) {
            router.dispatch(
                kind = NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
                discordIds = listOf(1L),
                guildId = guildId,
            ) {
                channel(route = ChannelRouteKey.LOTTERY) { msg("ch") }
                push { id -> PushPayload("a", "$id") }
                push { id -> PushPayload("b", "$id") }
            }
        }
    }

    @Test
    fun `multi-recipient dispatch rejects duplicate channel builder`() {
        assertThrows(IllegalStateException::class.java) {
            router.dispatch(
                kind = NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
                discordIds = listOf(1L),
                guildId = guildId,
            ) {
                channel(route = ChannelRouteKey.LOTTERY) { msg("ch1") }
                channel(route = ChannelRouteKey.LOTTERY) { msg("ch2") }
                push { id -> PushPayload("t", "$id") }
            }
        }
    }

    @Test
    fun `multi-recipient dispatch ignores configured dm-or-push builders when kind does not support them`() {
        // LOTTERY_DRAW_WITH_MY_TICKET supports CHANNEL + PUSH only.
        // Configuring dm{} on a multi-dispatch for it should be silently
        // skipped (no sendDm call, builder not invoked).
        var dmBuilderInvoked = false
        router.dispatch(
            kind = NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
            discordIds = listOf(1L),
            guildId = guildId,
        ) {
            channel(route = ChannelRouteKey.LOTTERY) { msg("ch") }
            push { id -> PushPayload("t", "$id") }
            dm { _ ->
                dmBuilderInvoked = true
                msg("dm")
            }
        }
        verify(exactly = 0) { router.sendDm(any(), any(), any(), any()) }
        assert(!dmBuilderInvoked)
    }

    private fun msg(text: String): MessageCreateData =
        MessageCreateBuilder().setContent(text).build()
}
