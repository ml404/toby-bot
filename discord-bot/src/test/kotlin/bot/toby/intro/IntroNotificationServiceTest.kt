package bot.toby.intro

import common.notification.NotificationChannelKind
import common.notification.Surface
import database.service.user.UserNotificationPrefService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * The nudge is now a single message with a link to the guild's intro page —
 * no reply parsing, no waiter, nothing to time out. What's left to protect is
 * when it fires (opt-out, cooldown) and that the message carries the link.
 */
class IntroNotificationServiceTest {

    private val discordId = 100L
    private val guildId = 42L

    private lateinit var user: User
    private lateinit var guild: Guild
    private lateinit var prefService: UserNotificationPrefService
    private lateinit var service: IntroNotificationService

    @BeforeEach
    fun setup() {
        user = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        prefService = mockk(relaxed = true)
        every { user.idLong } returns discordId
        every { guild.idLong } returns guildId
        every { guild.name } returns "Test Guild"
        every {
            prefService.isOptedIn(any(), any(), NotificationChannelKind.INTRO_PROMPT, Surface.DM)
        } returns true

        service = IntroNotificationService(prefService)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun userWith(id: Long): User = mockk(relaxed = true) { every { idLong } returns id }
    private fun guildWith(id: Long): Guild = mockk(relaxed = true) {
        every { idLong } returns id
        every { name } returns "Other Guild"
    }

    // --- when it fires ------------------------------------------------------

    @Test
    fun `an opted-out user gets no DM at all`() {
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.DM)
        } returns false

        service.promptUserForMusicInfo(user, guild)

        verify(exactly = 0) { user.openPrivateChannel() }
    }

    @Test
    fun `an opted-in user is prompted`() {
        service.promptUserForMusicInfo(user, guild)

        verify(exactly = 1) { user.openPrivateChannel() }
    }

    @Test
    fun `no preference service means no gate, matching the legacy default`() {
        val ungated = IntroNotificationService(null)

        ungated.promptUserForMusicInfo(user, guild)

        verify(exactly = 1) { user.openPrivateChannel() }
    }

    @Test
    fun `repeat joins inside the cooldown do not re-prompt`() {
        repeat(3) { service.promptUserForMusicInfo(user, guild) }

        verify(exactly = 1) { user.openPrivateChannel() }
    }

    @Test
    fun `the cooldown is per guild`() {
        service.promptUserForMusicInfo(user, guild)
        service.promptUserForMusicInfo(user, guildWith(999L))

        // Same person, different server — they genuinely have no intro there.
        verify(exactly = 2) { user.openPrivateChannel() }
    }

    @Test
    fun `the cooldown is per user`() {
        val other = userWith(999L)

        service.promptUserForMusicInfo(user, guild)
        service.promptUserForMusicInfo(other, guild)

        verify(exactly = 1) { user.openPrivateChannel() }
        verify(exactly = 1) { other.openPrivateChannel() }
    }

    @Test
    fun `an opted-out user is never recorded, so opting back in still prompts`() {
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.DM)
        } returns false
        service.promptUserForMusicInfo(user, guild)

        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.DM)
        } returns true
        service.promptUserForMusicInfo(user, guild)

        verify(exactly = 1) { user.openPrivateChannel() }
    }

    // --- what it says -------------------------------------------------------

    @Test
    fun `the DM carries an embed naming the guild and a link to its intro page`() {
        val channel = mockk<PrivateChannel>(relaxed = true)
        val send = mockk<MessageCreateAction>(relaxed = true)
        val embeds = mutableListOf<MessageEmbed>()
        val rows = mutableListOf<ActionRow>()
        every { channel.sendMessageEmbeds(capture(embeds)) } returns send
        every { send.setComponents(capture(rows)) } returns send

        val callback = slot<Consumer<PrivateChannel>>()
        val open = mockk<net.dv8tion.jda.api.requests.restaction.CacheRestAction<PrivateChannel>>(relaxed = true)
        every { user.openPrivateChannel() } returns open
        every { open.queue(capture(callback)) } returns Unit

        service.promptUserForMusicInfo(user, guild)
        callback.captured.accept(channel)

        val embed = embeds.single()
        assertTrue(embed.title!!.contains("Test Guild"), embed.title!!)
        assertTrue(embed.description!!.contains("/setintro"), "keeps the in-Discord route: ${embed.description}")
        assertTrue(
            embed.footer!!.text!!.contains("INTRO_PROMPT"),
            "tells people how to turn it off: ${embed.footer?.text}",
        )

        val button = rows.single().buttons.single()
        assertEquals("https://www.toby-bot.co.uk/intro/$guildId", button.url)
    }

    @Test
    fun `the DM is not scheduled for deletion`() {
        // The old prompt deleted itself after five minutes, which made it
        // useless to anyone who came back to it later. A link doesn't expire.
        val channel = mockk<PrivateChannel>(relaxed = true)
        val send = mockk<MessageCreateAction>(relaxed = true)
        every { channel.sendMessageEmbeds(any<MessageEmbed>()) } returns send
        every { send.setComponents(any<ActionRow>()) } returns send

        val callback = slot<Consumer<PrivateChannel>>()
        val open = mockk<net.dv8tion.jda.api.requests.restaction.CacheRestAction<PrivateChannel>>(relaxed = true)
        every { user.openPrivateChannel() } returns open
        every { open.queue(capture(callback)) } returns Unit

        service.promptUserForMusicInfo(user, guild)
        callback.captured.accept(channel)

        verify { send.queue() }
        verify(exactly = 0) { send.queue(any<Consumer<Message>>()) }
    }
}
