package web.service

import database.dto.AchievementDto
import database.dto.LoginStreakDto
import database.dto.UserDto
import database.service.AchievementService
import database.service.AchievementService.AchievementView
import database.service.LoginStreakService
import database.service.TitleService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Locks in the streak + achievement shape on the profile view. Verifies
 * that ProfileWebService.getProfile rolls login-streak and achievement
 * data into the view exactly the way the Thymeleaf template consumes it.
 */
class ProfileWebServiceEngagementTest {

    private val discordId = 100L
    private val guildId = 42L

    private lateinit var jda: JDA
    private lateinit var userService: UserService
    private lateinit var titleService: TitleService
    private lateinit var introWebService: IntroWebService
    private lateinit var membership: GuildMembership
    private lateinit var loginStreakService: LoginStreakService
    private lateinit var achievementService: AchievementService
    private lateinit var service: ProfileWebService

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        membership = mockk(relaxed = true)
        loginStreakService = mockk(relaxed = true)
        achievementService = mockk(relaxed = true)
        service = ProfileWebService(
            jda, userService, titleService, introWebService, membership,
            loginStreakService, achievementService
        )

        val guild = mockk<Guild>(relaxed = true) {
            every { id } returns guildId.toString()
            every { name } returns "Test Guild"
        }
        val member = mockk<Member>(relaxed = true) {
            every { effectiveName } returns "Tester"
            every { effectiveAvatarUrl } returns "https://example.com/avatar.png"
            every { isOwner } returns false
        }
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns member
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { socialCredit = 100L; xp = 0L }
        every { titleService.listOwned(discordId) } returns emptyList()
    }

    @Test
    fun `profile view includes streak summary when the user has claimed`() {
        val today = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC)
        every { loginStreakService.get(discordId, guildId) } returns LoginStreakDto(
            discordId = discordId, guildId = guildId,
            currentStreak = 5, longestStreak = 12,
            lastClaimDate = today, totalClaims = 30L
        )
        every { achievementService.listFor(discordId, guildId) } returns emptyList()

        val view = service.getProfile(discordId, guildId)!!
        assertEquals(5, view.streak.currentStreak)
        assertEquals(12, view.streak.longestStreak)
        assertEquals(today.toString(), view.streak.lastClaimDate)
        assertTrue(view.streak.claimedToday, "claimedToday must reflect last_claim_date == today UTC")
    }

    @Test
    fun `profile view streak claimedToday flips false when the row is yesterday`() {
        val yesterday = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC).minusDays(1)
        every { loginStreakService.get(discordId, guildId) } returns LoginStreakDto(
            discordId = discordId, guildId = guildId,
            currentStreak = 3, longestStreak = 7, lastClaimDate = yesterday, totalClaims = 5L
        )
        every { achievementService.listFor(discordId, guildId) } returns emptyList()

        val view = service.getProfile(discordId, guildId)!!
        assertFalse(view.streak.claimedToday)
    }

    @Test
    fun `profile view returns zero streak when user has never claimed`() {
        every { loginStreakService.get(discordId, guildId) } returns null
        every { achievementService.listFor(discordId, guildId) } returns emptyList()

        val view = service.getProfile(discordId, guildId)!!
        assertEquals(0, view.streak.currentStreak)
        assertEquals(0, view.streak.longestStreak)
        assertEquals(null, view.streak.lastClaimDate)
        assertFalse(view.streak.claimedToday)
    }

    @Test
    fun `achievements field rolls up unlocked and total counts and entries`() {
        val unlockedSpec = AchievementDto(
            id = 1L, code = "u", name = "U", description = "ud", category = "level", threshold = 1
        )
        val lockedSpec = AchievementDto(
            id = 2L, code = "l", name = "L", description = "ld", category = "level", threshold = 10
        )
        every { loginStreakService.get(discordId, guildId) } returns null
        every { achievementService.listFor(discordId, guildId) } returns listOf(
            AchievementView(achievement = unlockedSpec, unlockedAt = Instant.parse("2026-05-01T12:00:00Z"), progress = 1L),
            AchievementView(achievement = lockedSpec, unlockedAt = null, progress = 4L),
        )

        val view = service.getProfile(discordId, guildId)!!
        assertEquals(1, view.achievementsUnlocked)
        assertEquals(2, view.achievementsTotal)
        assertEquals(2, view.achievements.size)
        val unlocked = view.achievements.first { it.code == "u" }
        assertTrue(unlocked.unlocked)
        val locked = view.achievements.first { it.code == "l" }
        assertFalse(locked.unlocked)
        assertEquals(4L, locked.progress)
        assertEquals(10L, locked.threshold)
    }
}
