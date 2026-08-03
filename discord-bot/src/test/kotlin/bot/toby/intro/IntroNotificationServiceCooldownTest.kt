package bot.toby.intro

import bot.toby.handler.EventWaiter
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.UserDtoHelper
import common.notification.NotificationChannelKind
import common.notification.Surface
import database.service.music.MusicFileService
import database.service.user.UserNotificationPrefService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.User
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `VoiceEventHandler` calls the prompt on *every* voice join while the user
 * has no intro. Without a cooldown a channel-hopper collected a DM per hop —
 * plus, five minutes later, a "you didn't respond in time" DM per hop, since
 * each prompt armed its own [EventWaiter].
 */
class IntroNotificationServiceCooldownTest {

    private val discordId = 100L
    private val guildId = 42L

    private lateinit var user: User
    private lateinit var guild: Guild
    private lateinit var prefService: UserNotificationPrefService
    private lateinit var eventWaiter: EventWaiter
    private lateinit var service: IntroNotificationService

    private fun newService() = IntroNotificationService(
        userDtoHelper = mockk<UserDtoHelper>(relaxed = true),
        musicFileService = mockk<MusicFileService>(relaxed = true),
        httpHelper = mockk<HttpHelper>(relaxed = true),
        eventWaiter = eventWaiter,
        validationService = mockk<IntroValidationService>(relaxed = true),
        mediaLoader = mockk<IntroMediaLoader>(relaxed = true),
        notificationPrefService = prefService,
    )

    private fun otherUser(id: Long): User = mockk(relaxed = true) { every { idLong } returns id }
    private fun otherGuild(id: Long): Guild = mockk(relaxed = true) {
        every { idLong } returns id
        every { name } returns "Other Guild"
    }

    @BeforeEach
    fun setup() {
        user = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        prefService = mockk(relaxed = true)
        eventWaiter = mockk(relaxed = true)
        every { user.idLong } returns discordId
        every { guild.idLong } returns guildId
        every { guild.name } returns "Test Guild"
        every {
            prefService.isOptedIn(any(), any(), NotificationChannelKind.INTRO_PROMPT, Surface.DM)
        } returns true

        service = newService()
    }

    @Test
    fun `a second join within the cooldown does not re-prompt`() {
        service.promptUserForMusicInfo(user, guild)
        service.promptUserForMusicInfo(user, guild)
        service.promptUserForMusicInfo(user, guild)

        verify(exactly = 1) { user.openPrivateChannel() }
    }

    @Test
    fun `the cooldown is per guild`() {
        val second = otherGuild(999L)

        service.promptUserForMusicInfo(user, guild)
        service.promptUserForMusicInfo(user, second)

        // Same user, different server — they genuinely have no intro there.
        verify(exactly = 2) { user.openPrivateChannel() }
    }

    @Test
    fun `the cooldown is per user`() {
        val second = otherUser(999L)

        service.promptUserForMusicInfo(user, guild)
        service.promptUserForMusicInfo(second, guild)

        verify(exactly = 1) { user.openPrivateChannel() }
        verify(exactly = 1) { second.openPrivateChannel() }
    }

    @Test
    fun `an opted-out user is never recorded, so opting back in still prompts`() {
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.DM)
        } returns false
        service.promptUserForMusicInfo(user, guild)
        verify(exactly = 0) { user.openPrivateChannel() }

        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.DM)
        } returns true
        service.promptUserForMusicInfo(user, guild)

        verify(exactly = 1) { user.openPrivateChannel() }
    }

    @Test
    fun `only one waiter is armed per prompt`() {
        service.promptUserForMusicInfo(user, guild)
        service.promptUserForMusicInfo(user, guild)

        // openPrivateChannel is stubbed relaxed, so the waiter is set up in
        // its callback and never actually runs here — the assertion that
        // matters is that we didn't even open the channel a second time.
        verify(exactly = 1) { user.openPrivateChannel() }
    }
}
