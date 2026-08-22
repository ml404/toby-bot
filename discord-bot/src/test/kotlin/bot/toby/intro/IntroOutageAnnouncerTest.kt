package bot.toby.intro

import bot.toby.notify.NotificationRouter
import common.notification.ChannelRouteKey
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The one moment every intro on a server is silent used to be the one moment
 * nothing explained it: the failures are deliberately uncounted during an
 * outage, so no DM went out and every surface a member could reach still
 * described their intro as fine.
 */
class IntroOutageAnnouncerTest {

    private lateinit var router: NotificationRouter
    private lateinit var announcer: IntroOutageAnnouncer

    private val routes = mutableListOf<ChannelRouteKey>()
    private val guilds = mutableListOf<Long>()
    private val messages = mutableListOf<() -> MessageCreateData>()

    @BeforeEach
    fun setUp() {
        router = mockk(relaxed = true)
        announcer = IntroOutageAnnouncer(router)
        routes.clear()
        guilds.clear()
        messages.clear()
        every {
            router.sendChannel(capture(guilds), capture(routes), any(), capture(messages), any(), any())
        } returns Unit
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun body(): String =
        messages.single()().embeds.single().let { "${it.title} ${it.description} ${it.footer?.text}" }

    @Test
    fun `it posts to the guild that felt the outage`() {
        announcer.onSourceOutageNoticed(SourceOutageNoticedEvent(7L))

        assertEquals(listOf(7L), guilds)
    }

    @Test
    fun `it falls back to the server's default channel`() {
        // Unlike a broken intro, which is one member's business and stays
        // opt-in, an outage is everybody's — and it is exactly when a server
        // is most likely to conclude the bot is simply broken.
        announcer.onSourceOutageNoticed(SourceOutageNoticedEvent(7L))

        assertEquals(ChannelRouteKey.INTRO_OUTAGE, routes.single())
        assertTrue(ChannelRouteKey.INTRO_OUTAGE.systemChannelFallback)
    }

    @Test
    fun `it reuses the intro-issue channel when a server has set one`() {
        assertEquals("INTRO_ISSUE_CHANNEL", ChannelRouteKey.INTRO_OUTAGE.primaryConfigKey)
    }

    @Test
    fun `it says nobody's intro is broken`() {
        // "Nothing found for that link" was the sentence people got all
        // evening during the episode this came from, and it sent them off to
        // re-upload intros that had never stopped working.
        announcer.onSourceOutageNoticed(SourceOutageNoticedEvent(7L))

        val text = body()
        assertTrue(text.contains("Nobody's intro is broken"), text)
        assertTrue(text.contains("clears on its own"), text)
    }

    @Test
    fun `it does not tell anyone to go and fix something`() {
        announcer.onSourceOutageNoticed(SourceOutageNoticedEvent(7L))

        val text = body()
        assertTrue(!text.contains("/setintro"), text)
        assertTrue(!text.contains("replace"), text)
    }

    @Test
    fun `a router that throws does not escape into the audio path`() {
        // This runs from a Spring listener on the load path; a Discord hiccup
        // must not take playback with it.
        every { router.sendChannel(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("discord down")

        announcer.onSourceOutageNoticed(SourceOutageNoticedEvent(7L))
    }

    @Test
    fun `without a router it is a no-op`() {
        IntroOutageAnnouncer(null).onSourceOutageNoticed(SourceOutageNoticedEvent(7L))

        verify(exactly = 0) { router.sendChannel(any(), any(), any(), any(), any(), any()) }
    }
}
