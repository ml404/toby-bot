package bot.toby.notify

import common.notification.NotificationChannelKind
import common.notification.Surface
import database.service.ConfigService
import database.service.UserNotificationPrefService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.requests.restaction.CacheRestAction
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Coverage for [NotificationRouter.sendDm]. The DM surface is the
 * primary opt-in gate every notifier kind that fires a private message
 * relies on, so the router's behaviour here pins the contract:
 *
 *   1. Opt-out short-circuits before anything else (no JDA call, no
 *      payload built).
 *   2. Opt-in builds the payload lazily, retrieves the user, opens a
 *      private channel, sends the message.
 *   3. Each failure mode (retrieveUserById failure, null user, payload
 *      build throws, openPrivateChannel error callback) is swallowed
 *      and does not propagate — `sendDm` is best-effort.
 */
class NotificationRouterDmTest {

    private val guildId = 100L
    private val discordId = 200L
    private val kind = NotificationChannelKind.ACHIEVEMENT_UNLOCK

    private lateinit var jda: JDA
    private lateinit var prefService: UserNotificationPrefService
    private lateinit var configService: ConfigService
    private lateinit var router: NotificationRouter

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        prefService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        router = NotificationRouter(jda, prefService, configService, pushAdapter = null)
    }

    /** Counts how many times the payload-builder runs. */
    private class CountingBuilder : () -> MessageCreateData {
        var calls = 0
        override fun invoke(): MessageCreateData {
            calls++
            return MessageCreateBuilder().setContent("hi").build()
        }
    }

    private fun stubRetrieveUser(user: User?) {
        val action = mockk<CacheRestAction<User>>(relaxed = true)
        every { action.complete() } returns user
        every { jda.retrieveUserById(discordId) } returns action
    }

    private fun stubRetrieveUserThrows(error: Throwable) {
        val action = mockk<CacheRestAction<User>>(relaxed = true)
        every { action.complete() } throws error
        every { jda.retrieveUserById(discordId) } returns action
    }

    // ---------- opt-in gating ----------

    @Test
    fun `opted-out user short-circuits without building payload or touching JDA`() {
        every {
            prefService.isOptedIn(discordId, guildId, kind, Surface.DM)
        } returns false
        val builder = CountingBuilder()

        router.sendDm(discordId, guildId, kind, builder)

        assert(builder.calls == 0) { "payload builder must not run for opted-out users" }
        verify(exactly = 0) { jda.retrieveUserById(any<Long>()) }
    }

    @Test
    fun `opt-in check uses Surface dot DM, not any other surface`() {
        // The DM dispatch path must consult (kind, Surface.DM) — not
        // CHANNEL or PUSH — so a user opted in to CHANNEL but out of DM
        // still doesn't receive the DM.
        every {
            prefService.isOptedIn(discordId, guildId, kind, Surface.DM)
        } returns false
        every {
            prefService.isOptedIn(discordId, guildId, kind, Surface.CHANNEL)
        } returns true

        router.sendDm(discordId, guildId, kind, CountingBuilder())

        verify(exactly = 1) { prefService.isOptedIn(discordId, guildId, kind, Surface.DM) }
        verify(exactly = 0) { prefService.isOptedIn(discordId, guildId, kind, Surface.CHANNEL) }
        verify(exactly = 0) { prefService.isOptedIn(discordId, guildId, kind, Surface.PUSH) }
    }

    // ---------- happy path ----------

    @Test
    fun `opted-in user retrieves user, opens private channel, sends message`() {
        every {
            prefService.isOptedIn(discordId, guildId, kind, Surface.DM)
        } returns true
        val user = mockk<User>(relaxed = true)
        stubRetrieveUser(user)

        val openAction = mockk<CacheRestAction<PrivateChannel>>(relaxed = true)
        val privateChannel = mockk<PrivateChannel>(relaxed = true)
        val sendAction = mockk<MessageCreateAction>(relaxed = true)
        every { user.openPrivateChannel() } returns openAction
        every { privateChannel.sendMessage(any<MessageCreateData>()) } returns sendAction

        val onSuccess = slot<Consumer<PrivateChannel>>()
        every { openAction.queue(capture(onSuccess), any()) } answers {
            onSuccess.captured.accept(privateChannel)
        }
        every { sendAction.queue(any(), any()) } just runs

        val payload = MessageCreateBuilder().setContent("hello").build()
        router.sendDm(discordId, guildId, kind) { payload }

        verify(exactly = 1) { jda.retrieveUserById(discordId) }
        verify(exactly = 1) { user.openPrivateChannel() }
        verify(exactly = 1) { privateChannel.sendMessage(payload) }
    }

    @Test
    fun `payload builder is invoked lazily after opt-in passes`() {
        every {
            prefService.isOptedIn(discordId, guildId, kind, Surface.DM)
        } returns true
        val user = mockk<User>(relaxed = true)
        stubRetrieveUser(user)
        every { user.openPrivateChannel() } returns
            mockk<CacheRestAction<PrivateChannel>>(relaxed = true)

        val builder = CountingBuilder()
        router.sendDm(discordId, guildId, kind, builder)

        assert(builder.calls == 1) {
            "payload builder must run exactly once when user is opted in (got ${builder.calls})"
        }
    }

    // ---------- failure modes ----------

    @Test
    fun `retrieveUserById complete() throwing is swallowed`() {
        every {
            prefService.isOptedIn(discordId, guildId, kind, Surface.DM)
        } returns true
        stubRetrieveUserThrows(RuntimeException("boom"))

        // Must not propagate — sendDm is best-effort.
        router.sendDm(discordId, guildId, kind, CountingBuilder())
    }

    @Test
    fun `null user from retrieveUserById is swallowed and no DM is opened`() {
        every {
            prefService.isOptedIn(discordId, guildId, kind, Surface.DM)
        } returns true
        stubRetrieveUser(null)

        // No throw + no further interactions on jda beyond the retrieve.
        router.sendDm(discordId, guildId, kind, CountingBuilder())

        verify(exactly = 1) { jda.retrieveUserById(discordId) }
    }

    @Test
    fun `payload builder throwing is swallowed and openPrivateChannel is not called`() {
        every {
            prefService.isOptedIn(discordId, guildId, kind, Surface.DM)
        } returns true
        val user = mockk<User>(relaxed = true)
        stubRetrieveUser(user)

        router.sendDm(discordId, guildId, kind) { error("payload boom") }

        verify(exactly = 0) { user.openPrivateChannel() }
    }

    @Test
    fun `openPrivateChannel error callback path is swallowed (no propagation)`() {
        every {
            prefService.isOptedIn(discordId, guildId, kind, Surface.DM)
        } returns true
        val user = mockk<User>(relaxed = true)
        stubRetrieveUser(user)

        val openAction = mockk<CacheRestAction<PrivateChannel>>(relaxed = true)
        every { user.openPrivateChannel() } returns openAction
        val errCb = slot<Consumer<in Throwable>>()
        every { openAction.queue(any(), capture(errCb)) } answers {
            errCb.captured.accept(RuntimeException("openPrivateChannel rejected"))
        }

        // Just ensure no throw escapes — error callback consumed it.
        router.sendDm(discordId, guildId, kind, CountingBuilder())
    }

    // ---------- kind coverage ----------

    @Test
    fun `every DM-supporting kind consults the (kind, Surface dot DM) pref before any JDA call`() {
        // Locks the per-surface contract: for every kind that declares
        // Surface.DM, sendDm must consult prefService.isOptedIn for
        // exactly (kind, Surface.DM) — never CHANNEL or PUSH — and must
        // short-circuit before reaching JDA when opt-in is false.
        NotificationChannelKind.entries
            .filter { it.supports(Surface.DM) }
            .forEach { k ->
                router.sendDm(discordId, guildId, k, CountingBuilder())
                verify(atLeast = 1) {
                    prefService.isOptedIn(discordId, guildId, k, Surface.DM)
                }
            }
        // Across the whole sweep, no JDA call escaped — opt-out (default
        // for the relaxed mock) gated every dispatch.
        verify(exactly = 0) { jda.retrieveUserById(any<Long>()) }
    }

}
