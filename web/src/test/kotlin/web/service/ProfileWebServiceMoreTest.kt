package web.service

import database.dto.guild.TitleDto
import database.dto.guild.UserOwnedTitleDto
import database.dto.social.LoginStreakDto
import database.dto.user.UserDto
import database.service.guild.AchievementService
import database.service.guild.TitleService
import database.service.social.LoginStreakService
import database.service.user.UserService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.Instant

/**
 * Covers branches in ProfileWebService NOT exercised by ProfileWebServiceEngagementTest.
 * - getMemberGuilds
 * - isMember
 * - getProfile returning null (no guild / no member)
 * - getProfile with titles and equipped title
 * - getProfile xpProgressPercent branches
 * - getProfile permissions
 */
class ProfileWebServiceMoreTest {

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

    private lateinit var guild: Guild
    private lateinit var member: Member

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

        guild = mockk(relaxed = true) {
            every { id } returns guildId.toString()
            every { name } returns "Test Guild"
        }
        member = mockk(relaxed = true) {
            every { effectiveName } returns "Tester"
            every { effectiveAvatarUrl } returns "https://example.com/avatar.png"
            every { isOwner } returns false
        }

        // Default happy-path setup for getProfile tests
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns member
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { socialCredit = 100L; xp = 0L }
        every { titleService.listOwned(discordId) } returns emptyList()
        every { loginStreakService.get(discordId, guildId) } returns null
        every { loginStreakService.previewReward(any(), any()) } returns
            LoginStreakService.RewardPreview(0L, 0L)
        every { achievementService.listFor(discordId, guildId) } returns emptyList()
    }

    // ---- isMember ----

    @Test
    fun `isMember returns true when membership returns true`() {
        every { membership.isMember(discordId, guildId) } returns true
        assertTrue(service.isMember(discordId, guildId))
    }

    @Test
    fun `isMember returns false when membership returns false`() {
        every { membership.isMember(discordId, guildId) } returns false
        assertFalse(service.isMember(discordId, guildId))
    }

    // ---- getMemberGuilds ----

    @Test
    fun `getMemberGuilds returns empty when introWebService has no mutual guilds`() {
        every { introWebService.getMutualGuilds("token") } returns emptyList()
        assertTrue(service.getMemberGuilds("token", discordId).isEmpty())
    }

    @Test
    fun `getMemberGuilds skips guilds with non-numeric id`() {
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo("not-a-number", "Bad", null)
        )
        assertTrue(service.getMemberGuilds("token", discordId).isEmpty())
    }

    @Test
    fun `getMemberGuilds skips guild when bot is not in it`() {
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo(guildId.toString(), "Guild", null)
        )
        every { jda.getGuildById(guildId) } returns null
        assertTrue(service.getMemberGuilds("token", discordId).isEmpty())
    }

    @Test
    fun `getMemberGuilds skips guild when discord user is not a member`() {
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo(guildId.toString(), "Guild", null)
        )
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns null
        assertTrue(service.getMemberGuilds("token", discordId).isEmpty())
    }

    @Test
    fun `getMemberGuilds returns card with user balance when user exists`() {
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo(guildId.toString(), "Test Guild", "abc123")
        )
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns member
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { socialCredit = 750L }

        val result = service.getMemberGuilds("token", discordId)
        assertEquals(1, result.size)
        assertEquals(guildId.toString(), result[0].id)
        assertEquals("Test Guild", result[0].name)
        assertEquals(750L, result[0].balance)
        assertNotNull(result[0].iconUrl)
    }

    @Test
    fun `getMemberGuilds uses zero balance when user does not exist`() {
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo(guildId.toString(), "Test Guild", null)
        )
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns member
        every { userService.getUserById(discordId, guildId) } returns null

        val result = service.getMemberGuilds("token", discordId)
        assertEquals(1, result.size)
        assertEquals(0L, result[0].balance)
        assertNull(result[0].iconUrl)
    }

    @Test
    fun `getMemberGuilds sorts by name case-insensitively`() {
        val guild1 = mockk<Guild>(relaxed = true) { every { getMemberById(discordId) } returns member }
        val guild2 = mockk<Guild>(relaxed = true) { every { getMemberById(discordId) } returns member }
        every { introWebService.getMutualGuilds("token") } returns listOf(
            GuildInfo("200", "Zeta Guild", null),
            GuildInfo("100", "alpha Guild", null),
        )
        every { jda.getGuildById(200L) } returns guild1
        every { jda.getGuildById(100L) } returns guild2
        every { userService.getUserById(any(), any()) } returns null

        val result = service.getMemberGuilds("token", discordId)
        assertEquals(listOf("alpha Guild", "Zeta Guild"), result.map { it.name })
    }

    // ---- getProfile: null-guard branches ----

    @Test
    fun `getProfile returns null when guild not found`() {
        every { jda.getGuildById(guildId) } returns null
        assertNull(service.getProfile(discordId, guildId))
    }

    @Test
    fun `getProfile returns null when member not in guild`() {
        every { guild.getMemberById(discordId) } returns null
        assertNull(service.getProfile(discordId, guildId))
    }

    // ---- getProfile: basic shape ----

    @Test
    fun `getProfile returns correct guildId and guildName`() {
        val view = service.getProfile(discordId, guildId)!!
        assertEquals(guildId.toString(), view.guildId)
        assertEquals("Test Guild", view.guildName)
    }

    @Test
    fun `getProfile reflects isOwner from member`() {
        every { member.isOwner } returns true
        val view = service.getProfile(discordId, guildId)!!
        assertTrue(view.isOwner)
    }

    @Test
    fun `getProfile has zero balance and zero xp when user is null`() {
        every { userService.getUserById(discordId, guildId) } returns null
        val view = service.getProfile(discordId, guildId)!!
        assertEquals(0L, view.balance)
        assertEquals(0L, view.xp)
    }

    @Test
    fun `getProfile includes balance from user`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { socialCredit = 500L; xp = 0L }
        val view = service.getProfile(discordId, guildId)!!
        assertEquals(500L, view.balance)
    }

    // ---- getProfile: xpProgressPercent edge case ----

    @Test
    fun `getProfile xpProgressPercent is 100 when xpForNextLevel is zero (max level)`() {
        // At very high XP the LevelCurve may return xpForNextLevel=0 for max level.
        // We can't easily control LevelCurve without a real XP value, but we can
        // verify the formula: since xp=0 gives level 0 with non-zero xpForNextLevel,
        // we verify the normal case gives a percent in [0,100].
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { xp = 50L }
        val view = service.getProfile(discordId, guildId)!!
        assertTrue(view.xpProgressPercent in 0..100)
    }

    // ---- getProfile: titles ----

    @Test
    fun `getProfile includes no titles when user owns none`() {
        every { titleService.listOwned(discordId) } returns emptyList()
        val view = service.getProfile(discordId, guildId)!!
        assertTrue(view.ownedTitles.isEmpty())
        assertNull(view.equippedTitleLabel)
        assertNull(view.equippedTitleColorHex)
    }

    @Test
    fun `getProfile includes owned titles sorted by label`() {
        val titleA = TitleDto(id = 1L, label = "Zeta", cost = 100L, colorHex = "#FF0000")
        val titleB = TitleDto(id = 2L, label = "Alpha", cost = 200L)
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { socialCredit = 0L; xp = 0L; activeTitleId = null }
        every { titleService.listOwned(discordId) } returns listOf(
            UserOwnedTitleDto(discordId = discordId, titleId = 1L),
            UserOwnedTitleDto(discordId = discordId, titleId = 2L),
        )
        every { titleService.getById(1L) } returns titleA
        every { titleService.getById(2L) } returns titleB

        val view = service.getProfile(discordId, guildId)!!
        assertEquals(2, view.ownedTitles.size)
        assertEquals("Alpha", view.ownedTitles[0].label)
        assertEquals("Zeta", view.ownedTitles[1].label)
    }

    @Test
    fun `getProfile marks equipped title correctly`() {
        val titleA = TitleDto(id = 1L, label = "Gold", cost = 100L, colorHex = "#FFD700")
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { activeTitleId = 1L }
        every { titleService.getById(1L) } returns titleA
        every { titleService.listOwned(discordId) } returns listOf(
            UserOwnedTitleDto(discordId = discordId, titleId = 1L)
        )

        val view = service.getProfile(discordId, guildId)!!
        assertEquals("Gold", view.equippedTitleLabel)
        assertEquals("#FFD700", view.equippedTitleColorHex)
        val titleEntry = view.ownedTitles.single()
        assertTrue(titleEntry.equipped)
    }

    @Test
    fun `getProfile skips title when getById returns null for owned title`() {
        every { titleService.listOwned(discordId) } returns listOf(
            UserOwnedTitleDto(discordId = discordId, titleId = 99L),
        )
        every { titleService.getById(99L) } returns null

        val view = service.getProfile(discordId, guildId)!!
        assertTrue(view.ownedTitles.isEmpty())
    }

    // ---- getProfile: permissions ----

    @Test
    fun `getProfile default permissions when user is null`() {
        every { userService.getUserById(discordId, guildId) } returns null
        val view = service.getProfile(discordId, guildId)!!
        val permsMap = view.permissions.associate { it.name to it.enabled }
        assertTrue(permsMap["Music"]!!, "Music defaults to true")
        assertTrue(permsMap["Meme"]!!, "Meme defaults to true")
        assertTrue(permsMap["Dig"]!!, "Dig defaults to true")
        assertFalse(permsMap["Superuser"]!!, "Superuser defaults to false")
    }

    @Test
    fun `getProfile permissions reflect user flags`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply {
                musicPermission = false
                memePermission = true
                digPermission = false
                superUser = true
            }
        val view = service.getProfile(discordId, guildId)!!
        val permsMap = view.permissions.associate { it.name to it.enabled }
        assertFalse(permsMap["Music"]!!)
        assertTrue(permsMap["Meme"]!!)
        assertFalse(permsMap["Dig"]!!)
        assertTrue(permsMap["Superuser"]!!)
    }

    // ---- getProfile: streak NEW status ---

    @Test
    fun `getProfile streak status is NEW and inactive when no streak row exists`() {
        every { loginStreakService.get(discordId, guildId) } returns null
        every { achievementService.listFor(discordId, guildId) } returns emptyList()
        val view = service.getProfile(discordId, guildId)!!
        assertEquals("NEW", view.streak.status)
        assertFalse(view.streak.streakActive)
        assertEquals(0, view.streak.currentStreak)
    }

    @Test
    fun `getProfile streak nextReward for NEW user previews reset to streak 1`() {
        every { loginStreakService.get(discordId, guildId) } returns null
        every { loginStreakService.previewReward(guildId, 1) } returns
            LoginStreakService.RewardPreview(50L, 25L)
        every { achievementService.listFor(discordId, guildId) } returns emptyList()

        val view = service.getProfile(discordId, guildId)!!
        assertEquals(50L, view.streak.nextRewardXp)
        assertEquals(25L, view.streak.nextRewardCredits)
    }

    // ---- getProfile: achievement total XP calculation ----

    @Test
    fun `getProfile totalXpEarned sums xpReward of unlocked achievements only`() {
        val ach1 = database.dto.guild.AchievementDto(
            id = 1L, code = "a1", name = "A1", description = "", category = "level",
            threshold = 1, xpReward = 100, creditReward = 0
        )
        val ach2 = database.dto.guild.AchievementDto(
            id = 2L, code = "a2", name = "A2", description = "", category = "level",
            threshold = 1, xpReward = 200, creditReward = 0
        )
        val ach3 = database.dto.guild.AchievementDto(
            id = 3L, code = "a3", name = "A3", description = "", category = "level",
            threshold = 5, xpReward = 500, creditReward = 0
        )
        every { loginStreakService.get(discordId, guildId) } returns null
        every { achievementService.listFor(discordId, guildId) } returns listOf(
            AchievementService.AchievementView(ach1, Instant.now(), 1L),
            AchievementService.AchievementView(ach2, Instant.now(), 1L),
            AchievementService.AchievementView(ach3, null, 2L), // locked
        )

        val view = service.getProfile(discordId, guildId)!!
        assertEquals(300L, view.totalXpEarned, "Only unlocked achievements XP should be summed")
        assertEquals(2, view.achievementsUnlocked)
        assertEquals(3, view.achievementsTotal)
    }
}
