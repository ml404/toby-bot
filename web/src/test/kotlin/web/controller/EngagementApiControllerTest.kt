package web.controller

import common.notification.NotificationChannelKind
import database.dto.AchievementDto
import database.dto.LoginStreakDto
import database.dto.UserNotificationPrefDto
import database.service.AchievementService
import database.service.AchievementService.AchievementView
import database.service.LoginStreakService
import database.service.LoginStreakService.ClaimResult
import database.service.UserNotificationPrefService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.util.GuildMembership
import java.time.Instant
import java.time.LocalDate

class EngagementApiControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var loginStreakService: LoginStreakService
    private lateinit var achievementService: AchievementService
    private lateinit var notificationPrefService: UserNotificationPrefService
    private lateinit var membership: GuildMembership
    private lateinit var user: OAuth2User
    private lateinit var controller: EngagementApiController

    @BeforeEach
    fun setup() {
        loginStreakService = mockk(relaxed = true)
        achievementService = mockk(relaxed = true)
        notificationPrefService = mockk(relaxed = true)
        membership = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
        }
        every { membership.isMember(discordId, guildId) } returns true
        controller = EngagementApiController(
            loginStreakService, achievementService, notificationPrefService, membership
        )
    }

    // ---------- daily claim ----------

    @Test
    fun `claimDaily unauthenticated returns 401`() {
        val anon = mockk<OAuth2User> { every { getAttribute<String>("id") } returns null }
        val response = controller.claimDaily(guildId, anon)
        assertEquals(401, response.statusCode.value())
    }

    @Test
    fun `claimDaily for non-member returns 403`() {
        every { membership.isMember(discordId, guildId) } returns false
        val response = controller.claimDaily(guildId, user)
        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { loginStreakService.claim(any(), any(), any(), any()) }
    }

    @Test
    fun `claimDaily Granted maps to 200 with status granted`() {
        every { loginStreakService.claim(discordId, guildId, any(), any()) } returns
            ClaimResult.Granted(
                currentStreak = 5,
                longestStreak = 5,
                xpGranted = 70L,
                creditsGranted = 45L,
                isNewBest = true
            )

        val response = controller.claimDaily(guildId, user)

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals("granted", body.status)
        assertEquals(5, body.currentStreak)
        assertEquals(5, body.longestStreak)
        assertEquals(70L, body.xpGranted)
        assertEquals(45L, body.creditsGranted)
        assertTrue(body.newBest)
    }

    @Test
    fun `claimDaily AlreadyClaimed maps to 200 with status already_claimed and zero rewards`() {
        every { loginStreakService.claim(discordId, guildId, any(), any()) } returns
            ClaimResult.AlreadyClaimed(currentStreak = 3, longestStreak = 7)

        val response = controller.claimDaily(guildId, user)

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals("already_claimed", body.status)
        assertEquals(3, body.currentStreak)
        assertEquals(7, body.longestStreak)
        assertEquals(0L, body.xpGranted)
        assertEquals(0L, body.creditsGranted)
        assertFalse(body.newBest)
    }

    // ---------- streak status ----------

    @Test
    fun `streakStatus returns zeros when the user has never claimed`() {
        every { loginStreakService.get(discordId, guildId) } returns null
        val response = controller.streakStatus(guildId, user)
        val body = response.body!!
        assertEquals(0, body.currentStreak)
        assertEquals(0, body.longestStreak)
        assertEquals(0L, body.totalClaims)
        assertNull(body.lastClaimDate)
    }

    @Test
    fun `streakStatus serialises the row`() {
        every { loginStreakService.get(discordId, guildId) } returns LoginStreakDto(
            discordId = discordId,
            guildId = guildId,
            currentStreak = 5,
            longestStreak = 12,
            lastClaimDate = LocalDate.of(2026, 5, 10),
            totalClaims = 99L
        )

        val body = controller.streakStatus(guildId, user).body!!
        assertEquals(5, body.currentStreak)
        assertEquals(12, body.longestStreak)
        assertEquals("2026-05-10", body.lastClaimDate)
        assertEquals(99L, body.totalClaims)
    }

    // ---------- achievements ----------

    @Test
    fun `listAchievements maps unlocked and locked views`() {
        val unlockedSpec = AchievementDto(id = 1L, code = "u", name = "U", description = "ud", category = "level", threshold = 1)
        val lockedSpec = AchievementDto(id = 2L, code = "l", name = "L", description = "ld", category = "level", threshold = 10)
        every { achievementService.listFor(discordId, guildId) } returns listOf(
            AchievementView(achievement = unlockedSpec, unlockedAt = Instant.parse("2026-05-01T12:00:00Z"), progress = 1L),
            AchievementView(achievement = lockedSpec, unlockedAt = null, progress = 3L),
        )

        val body = controller.listAchievements(guildId, user).body!!

        assertEquals(2, body.size)
        val unlocked = body.first { it.code == "u" }
        assertTrue(unlocked.unlocked)
        assertNotNull(unlocked.unlockedAt)
        val locked = body.first { it.code == "l" }
        assertFalse(locked.unlocked)
        assertNull(locked.unlockedAt)
        assertEquals(3L, locked.progress)
        assertEquals(10L, locked.threshold)
    }

    // ---------- notification prefs ----------

    @Test
    fun `listNotifications composes the catalogue with explicit overrides and default markers`() {
        every { notificationPrefService.listForUser(discordId, guildId) } returns listOf(
            UserNotificationPrefDto(
                discordId = discordId,
                guildId = guildId,
                channelKind = NotificationChannelKind.STREAK_REMINDER.name,
                optIn = true
            )
        )

        val body = controller.listNotifications(guildId, user).body!!

        assertEquals(NotificationChannelKind.entries.size, body.size)

        val explicit = body.first { it.kind == NotificationChannelKind.STREAK_REMINDER.name }
        assertTrue(explicit.optIn, "explicit STREAK_REMINDER row should be opt-in")
        assertFalse(explicit.isDefault, "explicit row should not be marked default")

        val defaulted = body.first { it.kind == NotificationChannelKind.DUEL_OFFER.name }
        assertTrue(defaulted.optIn, "DUEL_OFFER default is opt-in")
        assertTrue(defaulted.isDefault, "no explicit row → isDefault true")
    }

    @Test
    fun `setNotification persists and returns the new row`() {
        every {
            notificationPrefService.setPref(discordId, guildId, NotificationChannelKind.PRICE_ALERT, true)
        } returns UserNotificationPrefDto(
            discordId = discordId,
            guildId = guildId,
            channelKind = NotificationChannelKind.PRICE_ALERT.name,
            optIn = true
        )

        val response = controller.setNotification(
            guildId, "PRICE_ALERT",
            EngagementApiController.NotificationPrefUpdate(optIn = true),
            user
        )

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals(NotificationChannelKind.PRICE_ALERT.name, body.kind)
        assertTrue(body.optIn)
        assertFalse(body.isDefault)
        verify { notificationPrefService.setPref(discordId, guildId, NotificationChannelKind.PRICE_ALERT, true) }
    }

    @Test
    fun `setNotification with unknown kind returns 400`() {
        val response = controller.setNotification(
            guildId, "NOT_A_KIND",
            EngagementApiController.NotificationPrefUpdate(optIn = true),
            user
        )
        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { notificationPrefService.setPref(any(), any(), any(), any()) }
    }

    @Test
    fun `setNotification rejects non-member with 403`() {
        every { membership.isMember(discordId, guildId) } returns false
        val response = controller.setNotification(
            guildId, "DUEL_OFFER",
            EngagementApiController.NotificationPrefUpdate(optIn = false),
            user
        )
        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { notificationPrefService.setPref(any(), any(), any(), any()) }
    }
}
