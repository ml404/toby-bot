package web.controller

import common.notification.NotificationChannelKind
import common.notification.Surface
import database.dto.guild.AchievementDto
import database.dto.social.LoginStreakDto
import database.dto.user.UserNotificationPrefDto
import database.service.guild.AchievementService
import database.service.guild.AchievementService.AchievementView
import database.service.social.LoginStreakService
import database.service.social.LoginStreakService.ClaimResult
import database.service.user.UserNotificationPrefService
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

    // ---------- notification prefs (per-surface) ----------

    @Test
    fun `listNotifications returns one entry per supported (kind, surface)`() {
        // No explicit rows — every entry should carry isDefault=true.
        every { notificationPrefService.listForUser(discordId, guildId) } returns emptyList()

        val body = controller.listNotifications(guildId, user).body!!

        val expectedCount = NotificationChannelKind.entries.sumOf { it.supportedSurfaces.size }
        assertEquals(expectedCount, body.size, "one response entry per supported (kind, surface)")

        // Every entry carries kind, surface, and the default's value.
        body.forEach { entry ->
            val kind = NotificationChannelKind.fromCode(entry.kind)!!
            val surface = Surface.valueOf(entry.surface)
            assertTrue(kind.supports(surface), "${entry.kind}+${entry.surface} should be supported")
            assertEquals(kind.defaultOptIn(surface), entry.optIn, "default value for ${entry.kind}+${entry.surface}")
            assertTrue(entry.isDefault, "no explicit row → isDefault true")
        }
    }

    @Test
    fun `listNotifications rejects unauthenticated with 401`() {
        val anon = mockk<OAuth2User> { every { getAttribute<String>("id") } returns null }
        val response = controller.listNotifications(guildId, anon)
        assertEquals(401, response.statusCode.value())
        verify(exactly = 0) { notificationPrefService.listForUser(any(), any()) }
    }

    @Test
    fun `listNotifications rejects non-member with 403`() {
        every { membership.isMember(discordId, guildId) } returns false
        val response = controller.listNotifications(guildId, user)
        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { notificationPrefService.listForUser(any(), any()) }
    }

    @Test
    fun `listNotifications surfaces explicit (kind, surface) overrides`() {
        // ACHIEVEMENT_UNLOCK supports DM, CHANNEL, PUSH. Opt out of DM,
        // leave the other two on their defaults.
        every { notificationPrefService.listForUser(discordId, guildId) } returns listOf(
            UserNotificationPrefDto(
                discordId = discordId,
                guildId = guildId,
                channelKind = NotificationChannelKind.ACHIEVEMENT_UNLOCK.name,
                surface = Surface.DM.name,
                optIn = false
            )
        )

        val body = controller.listNotifications(guildId, user).body!!
        val achDm = body.first { it.kind == "ACHIEVEMENT_UNLOCK" && it.surface == "DM" }
        assertFalse(achDm.optIn)
        assertFalse(achDm.isDefault, "explicit row → isDefault false")

        val achChannel = body.first { it.kind == "ACHIEVEMENT_UNLOCK" && it.surface == "CHANNEL" }
        assertTrue(achChannel.optIn, "CHANNEL surface unaffected by DM opt-out")
        assertTrue(achChannel.isDefault)
    }

    @Test
    fun `setNotification persists for supported (kind, surface) and returns the new row`() {
        every {
            notificationPrefService.setPref(
                discordId, guildId,
                NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.DM, true
            )
        } returns UserNotificationPrefDto(
            discordId = discordId, guildId = guildId,
            channelKind = NotificationChannelKind.ACHIEVEMENT_UNLOCK.name,
            surface = Surface.DM.name, optIn = true
        )

        val response = controller.setNotification(
            guildId, "ACHIEVEMENT_UNLOCK", "DM",
            EngagementApiController.NotificationPrefUpdate(optIn = true),
            user
        )

        assertEquals(200, response.statusCode.value())
        val body = response.body!!
        assertEquals("ACHIEVEMENT_UNLOCK", body.kind)
        assertEquals("DM", body.surface)
        assertTrue(body.optIn)
        assertFalse(body.isDefault)
    }

    @Test
    fun `setNotification rejects unknown kind with 400`() {
        val response = controller.setNotification(
            guildId, "NOT_A_KIND", "DM",
            EngagementApiController.NotificationPrefUpdate(optIn = true),
            user
        )
        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { notificationPrefService.setPref(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setNotification rejects unknown surface code with 400`() {
        val response = controller.setNotification(
            guildId, "DUEL_OFFER", "NOT_A_SURFACE",
            EngagementApiController.NotificationPrefUpdate(optIn = true),
            user
        )
        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { notificationPrefService.setPref(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setNotification rejects unsupported (kind, surface) pair with 400`() {
        // TIP_RECEIVED is CHANNEL + PUSH only, not DM.
        val response = controller.setNotification(
            guildId, "TIP_RECEIVED", "DM",
            EngagementApiController.NotificationPrefUpdate(optIn = true),
            user
        )
        assertEquals(400, response.statusCode.value())
        verify(exactly = 0) { notificationPrefService.setPref(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setNotification rejects non-member with 403`() {
        every { membership.isMember(discordId, guildId) } returns false
        val response = controller.setNotification(
            guildId, "DUEL_OFFER", "CHANNEL",
            EngagementApiController.NotificationPrefUpdate(optIn = false),
            user
        )
        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { notificationPrefService.setPref(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setNotification rejects unauthenticated with 401`() {
        val anon = mockk<OAuth2User> { every { getAttribute<String>("id") } returns null }
        val response = controller.setNotification(
            guildId, "DUEL_OFFER", "CHANNEL",
            EngagementApiController.NotificationPrefUpdate(optIn = false),
            anon
        )
        assertEquals(401, response.statusCode.value())
        verify(exactly = 0) { notificationPrefService.setPref(any(), any(), any(), any(), any()) }
    }
}
