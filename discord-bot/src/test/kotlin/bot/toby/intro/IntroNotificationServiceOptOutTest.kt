package bot.toby.intro

import bot.toby.handler.EventWaiter
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.UserDtoHelper
import common.notification.NotificationChannelKind
import database.service.MusicFileService
import database.service.UserNotificationPrefService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.requests.restaction.CacheRestAction
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Locks in the INTRO_PROMPT opt-out gate added when
 * [IntroNotificationService] was wired to [UserNotificationPrefService].
 * Pre-refactor the prompt fired unconditionally; users couldn't suppress
 * the DM. Now `/notify set INTRO_PROMPT off` makes the bot silent.
 */
class IntroNotificationServiceOptOutTest {

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

        service = IntroNotificationService(
            userDtoHelper = mockk<UserDtoHelper>(relaxed = true),
            musicFileService = mockk<MusicFileService>(relaxed = true),
            httpHelper = mockk<HttpHelper>(relaxed = true),
            eventWaiter = mockk<EventWaiter>(relaxed = true),
            validationService = mockk<IntroValidationService>(relaxed = true),
            mediaLoader = mockk<IntroMediaLoader>(relaxed = true),
            notificationPrefService = prefService,
        )
    }

    @Test
    fun `opted-out user gets no DM and no waiter setup`() {
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT)
        } returns false

        service.promptUserForMusicInfo(user, guild)

        // openPrivateChannel is the first JDA call the prompt path makes
        // — if the gate worked we shouldn't reach it.
        verify(exactly = 0) { user.openPrivateChannel() }
    }

    @Test
    fun `opted-in user reaches the prompt path`() {
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT)
        } returns true
        val privateChannel: CacheRestAction<PrivateChannel> = mockk(relaxed = true)
        every { user.openPrivateChannel() } returns privateChannel

        service.promptUserForMusicInfo(user, guild)

        verify(exactly = 1) { user.openPrivateChannel() }
    }
}
