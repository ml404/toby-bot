package bot.toby.notify

import common.notification.NotificationChannelKind
import common.notification.PushAdapter
import common.notification.PushPayload
import common.notification.Surface
import database.service.guild.ConfigService
import database.service.user.UserNotificationPrefService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Coverage for [NotificationRouter.sendPush]. Two wiring modes:
 *
 *  - no adapter registered (dev/CI without VAPID keys) → opted-in
 *    push lands the one-shot "no push adapter wired" WARN and drops.
 *  - adapter registered (WebPushAdapter present) → opted-in push is
 *    forwarded; opted-out and unsupported pairs are still silent.
 *
 * In both modes the payload builder is invoked lazily — only after
 * the supports + opt-in guards pass.
 */
class NotificationRouterPushTest {

    private val guildId = 100L
    private val discordId = 200L

    private lateinit var jda: JDA
    private lateinit var prefService: UserNotificationPrefService
    private lateinit var configService: ConfigService

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        prefService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
    }

    private fun newRouter(adapter: PushAdapter? = null) =
        NotificationRouter(jda, prefService, configService, adapter)

    /** Counts how many times the payload-builder runs. */
    private class CountingBuilder : () -> PushPayload {
        var calls = 0
        override fun invoke(): PushPayload {
            calls++
            return PushPayload(title = "t", body = "b")
        }
    }

    // ---------- guards (apply with or without adapter) ----------

    @Test
    fun `kind without PUSH support short-circuits without checking opt-in`() {
        // INTRO_PROMPT is DM-only.
        val builder = CountingBuilder()
        val adapter = mockk<PushAdapter>(relaxed = true)

        newRouter(adapter).sendPush(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, builder)

        assertEquals(0, builder.calls, "builder must not be invoked when surface unsupported")
        verify(exactly = 0) { prefService.isOptedIn(any(), any(), any(), any()) }
        verify(exactly = 0) { adapter.deliver(any(), any()) }
    }

    @Test
    fun `opted-out user short-circuits without invoking the builder`() {
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.PUSH)
        } returns false
        val builder = CountingBuilder()
        val adapter = mockk<PushAdapter>(relaxed = true)

        newRouter(adapter).sendPush(discordId, guildId, NotificationChannelKind.PRICE_ALERT, builder)

        assertEquals(0, builder.calls)
        verify(exactly = 0) { adapter.deliver(any(), any()) }
    }

    // ---------- adapter NOT wired ----------

    @Test
    fun `opted-in user with no adapter invokes the builder and triggers the missing-adapter warning`() {
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.PUSH)
        } returns true
        val builder = CountingBuilder()

        newRouter(adapter = null).sendPush(discordId, guildId, NotificationChannelKind.PRICE_ALERT, builder)

        assertEquals(1, builder.calls, "builder runs once when opt-in passes")
    }

    @Test
    fun `repeated sendPush calls log the missing-adapter warning only once per process`() {
        every {
            prefService.isOptedIn(any(), guildId, NotificationChannelKind.PRICE_ALERT, Surface.PUSH)
        } returns true
        val router = newRouter(adapter = null)

        repeat(10) { i ->
            val builder = CountingBuilder()
            router.sendPush(i.toLong() + 1L, guildId, NotificationChannelKind.PRICE_ALERT, builder)
            assertEquals(1, builder.calls, "builder runs for each opt-in passing call")
        }
        // No throw across 10 calls = the AtomicBoolean flipped exactly once.
    }

    // ---------- adapter WIRED ----------

    @Test
    fun `opted-in user with adapter forwards payload to adapter deliver`() {
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.PUSH)
        } returns true
        val adapter = mockk<PushAdapter>(relaxed = true)
        val builder = CountingBuilder()

        newRouter(adapter).sendPush(discordId, guildId, NotificationChannelKind.PRICE_ALERT, builder)

        assertEquals(1, builder.calls)
        verify(exactly = 1) {
            adapter.deliver(
                discordId,
                match<PushPayload> { it.title == "t" && it.body == "b" }
            )
        }
    }

    @Test
    fun `adapter exception is swallowed and does not propagate to caller`() {
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.PUSH)
        } returns true
        val adapter = mockk<PushAdapter>()
        every { adapter.deliver(any(), any()) } throws RuntimeException("simulated")
        // No throw = router caught it.
        newRouter(adapter).sendPush(discordId, guildId, NotificationChannelKind.PRICE_ALERT, CountingBuilder())
        verify(exactly = 1) { adapter.deliver(discordId, any()) }
    }

    @Test
    fun `payload builder failure does not invoke the adapter`() {
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.PUSH)
        } returns true
        val adapter = mockk<PushAdapter>(relaxed = true)

        newRouter(adapter).sendPush(
            discordId, guildId, NotificationChannelKind.PRICE_ALERT
        ) { throw RuntimeException("build broken") }

        verify(exactly = 0) { adapter.deliver(any(), any()) }
    }
}
