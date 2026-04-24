package bot.toby.handler

import bot.toby.activity.ActivityTrackingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.user.update.UserUpdateActivitiesEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ActivityEventHandlerTest {

    private lateinit var activityTrackingService: ActivityTrackingService
    private lateinit var handler: ActivityEventHandler

    @BeforeEach
    fun setup() {
        activityTrackingService = mockk(relaxed = true)
        handler = ActivityEventHandler(activityTrackingService)
    }

    private fun gameActivity(name: String): Activity {
        val a = mockk<Activity>(relaxed = true)
        every { a.type } returns Activity.ActivityType.PLAYING
        every { a.name } returns name
        return a
    }

    private fun musicActivity(name: String): Activity {
        val a = mockk<Activity>(relaxed = true)
        every { a.type } returns Activity.ActivityType.LISTENING
        every { a.name } returns name
        return a
    }

    private fun event(
        previous: List<Activity>,
        current: List<Activity>,
        discordId: Long = 1L,
        guildId: Long = 42L,
        isBot: Boolean = false
    ): UserUpdateActivitiesEvent {
        val e = mockk<UserUpdateActivitiesEvent>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        val user = mockk<User>(relaxed = true)
        val guild = mockk<Guild>(relaxed = true)
        every { user.isBot } returns isBot
        every { member.user } returns user
        every { member.idLong } returns discordId
        every { guild.idLong } returns guildId
        every { e.member } returns member
        every { e.guild } returns guild
        every { e.oldValue } returns previous
        every { e.newValue } returns current
        return e
    }

    @Test
    fun `opens session when user starts playing a game`() {
        handler.onUserUpdateActivities(event(previous = emptyList(), current = listOf(gameActivity("Minecraft"))))

        verify(exactly = 1) { activityTrackingService.openSession(1L, 42L, "Minecraft", any()) }
        verify(exactly = 0) { activityTrackingService.closeOpenSessionForUser(any(), any(), any()) }
    }

    @Test
    fun `closes session when user stops playing`() {
        handler.onUserUpdateActivities(event(previous = listOf(gameActivity("Minecraft")), current = emptyList()))

        verify(exactly = 1) { activityTrackingService.closeOpenSessionForUser(1L, 42L, any()) }
        verify(exactly = 0) { activityTrackingService.openSession(any(), any(), any(), any()) }
    }

    @Test
    fun `closes then opens when user switches games`() {
        handler.onUserUpdateActivities(event(
            previous = listOf(gameActivity("Minecraft")),
            current = listOf(gameActivity("Factorio"))
        ))

        verify(exactly = 1) { activityTrackingService.closeOpenSessionForUser(1L, 42L, any()) }
        verify(exactly = 1) { activityTrackingService.openSession(1L, 42L, "Factorio", any()) }
    }

    @Test
    fun `ignores non-PLAYING activities`() {
        handler.onUserUpdateActivities(event(
            previous = emptyList(),
            current = listOf(musicActivity("Spotify"))
        ))

        verify(exactly = 0) { activityTrackingService.openSession(any(), any(), any(), any()) }
        verify(exactly = 0) { activityTrackingService.closeOpenSessionForUser(any(), any(), any()) }
    }

    @Test
    fun `ignores bot accounts`() {
        handler.onUserUpdateActivities(event(
            previous = emptyList(),
            current = listOf(gameActivity("Minecraft")),
            isBot = true
        ))

        verify(exactly = 0) { activityTrackingService.openSession(any(), any(), any(), any()) }
    }

    @Test
    fun `no-op when primary game did not change`() {
        handler.onUserUpdateActivities(event(
            previous = listOf(gameActivity("Minecraft")),
            current = listOf(gameActivity("Minecraft"), musicActivity("Spotify"))
        ))

        verify(exactly = 0) { activityTrackingService.openSession(any(), any(), any(), any()) }
        verify(exactly = 0) { activityTrackingService.closeOpenSessionForUser(any(), any(), any()) }
    }
}
