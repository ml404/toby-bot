package web.service

import database.dto.AchievementDto
import database.dto.TitleDto
import database.dto.UserDto
import database.service.AchievementService
import database.service.TitleService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for [ProfileCardAggregator]. The aggregator combines
 * `UserDto` + leveling + title + achievement data into the renderer's
 * input, so this is the contract test that catches mis-wiring (e.g.
 * forgetting to filter unlocked achievements, picking the wrong sort
 * order, mishandling a null user record).
 *
 * The aggregator takes pre-resolved `Guild` and `Member` parameters —
 * not ids — so it has no `JDA` dependency. Callers are responsible for
 * the JDA lookup. See the class doc on [ProfileCardAggregator] for the
 * Spring-cycle reason.
 */
class ProfileCardAggregatorTest {

    private val guildId = 222L
    private val discordId = 42L

    private lateinit var userService: UserService
    private lateinit var titleService: TitleService
    private lateinit var achievementService: AchievementService
    private lateinit var guild: Guild
    private lateinit var member: Member
    private lateinit var aggregator: ProfileCardAggregator

    @BeforeEach
    fun setUp() {
        userService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        achievementService = mockk(relaxed = true)
        aggregator = ProfileCardAggregator(userService, titleService, achievementService)

        guild = mockk(relaxed = true)
        member = mockk(relaxed = true)
        every { guild.idLong } returns guildId
        every { guild.name } returns "Test Guild"
        every { member.idLong } returns discordId
        every { member.effectiveAvatarUrl } returns "https://avatar/42.png"
        every { member.effectiveName } returns "Alice"
    }

    @Test
    fun `build with no user row yields zero stats but still renders`() {
        every { userService.getUserById(discordId, guildId) } returns null
        every { achievementService.listFor(discordId, guildId) } returns emptyList()

        val data = aggregator.build(guild, member)
        assertEquals(0L, data.totalXp)
        assertEquals(0L, data.socialCredit)
        assertNull(data.equippedTitle)
        assertEquals(emptyList<Any>(), data.recentAchievements)
    }

    @Test
    fun `build resolves the equipped title via TitleService`() {
        val user = userDto(socialCredit = 1_000, xp = 500, activeTitleId = 7L)
        every { userService.getUserById(discordId, guildId) } returns user
        every { titleService.getById(7L) } returns TitleDto(id = 7L, label = "🌱 Sprout", cost = 200, colorHex = "#57F287")
        every { achievementService.listFor(discordId, guildId) } returns emptyList()

        val data = aggregator.build(guild, member)
        assertEquals("🌱 Sprout", data.equippedTitle?.label)
        assertEquals("#57F287", data.equippedTitle?.colorHex)
    }

    @Test
    fun `build leaves equippedTitle null when activeTitleId is null`() {
        every { userService.getUserById(discordId, guildId) } returns userDto(activeTitleId = null)
        every { achievementService.listFor(discordId, guildId) } returns emptyList()

        assertNull(aggregator.build(guild, member).equippedTitle)
    }

    @Test
    fun `build picks the three newest unlocked achievements newest-first`() {
        every { userService.getUserById(discordId, guildId) } returns userDto()
        val older = Instant.parse("2026-01-01T00:00:00Z")
        val middle = Instant.parse("2026-03-01T00:00:00Z")
        val newest = Instant.parse("2026-05-01T00:00:00Z")
        val olderStill = Instant.parse("2025-12-01T00:00:00Z")
        every { achievementService.listFor(discordId, guildId) } returns listOf(
            view("First Roll", "🎲", unlockedAt = older),
            view("5-day Streak", "🔥", unlockedAt = newest),
            view("Big Winner", "🏆", unlockedAt = middle),
            view("Locked One", "🎰", unlockedAt = null),
            view("Eldest", "📜", unlockedAt = olderStill),
        )

        val data = aggregator.build(guild, member)
        // Three returned, newest first, locked entries filtered.
        assertEquals(listOf("5-day Streak", "Big Winner", "First Roll"), data.recentAchievements.map { it.name })
        // No locked entries.
        assertEquals(emptySet<String>(), setOf("Locked One") intersect data.recentAchievements.map { it.name }.toSet())
        // Cap of MAX_RECENT_ACHIEVEMENTS — "Eldest" must NOT appear.
        assertEquals(ProfileCardAggregator.MAX_RECENT_ACHIEVEMENTS, data.recentAchievements.size)
    }

    @Test
    fun `build forwards the JDA avatar and display name`() {
        every { userService.getUserById(discordId, guildId) } returns userDto()
        every { achievementService.listFor(discordId, guildId) } returns emptyList()

        val data = aggregator.build(guild, member)
        assertEquals("https://avatar/42.png", data.avatarUrl)
        assertEquals("Alice", data.displayName)
        assertEquals("Test Guild", data.guildName)
    }

    @Test
    fun `build derives level math from total XP via LevelCurve`() {
        // 255 XP is exactly the cumulative XP to reach level 2 (100 + 155).
        // The LevelCurve itself is covered by its own unit tests; this
        // assertion just pins the passthrough.
        every { userService.getUserById(discordId, guildId) } returns userDto(xp = 255L)
        every { achievementService.listFor(discordId, guildId) } returns emptyList()

        val data = aggregator.build(guild, member)
        assertEquals(2, data.level)
        assertEquals(255L, data.totalXp)
        assertEquals(0L, data.xpIntoLevel)
    }

    // ---- helpers ----

    private fun userDto(
        socialCredit: Long? = 0L,
        xp: Long = 0L,
        activeTitleId: Long? = null,
    ) = UserDto(
        discordId = discordId,
        guildId = guildId,
        socialCredit = socialCredit,
        xp = xp,
        activeTitleId = activeTitleId,
    )

    private fun view(name: String, icon: String?, unlockedAt: Instant?) =
        AchievementService.AchievementView(
            achievement = AchievementDto(name = name, icon = icon, code = name.lowercase().replace(' ', '_')),
            unlockedAt = unlockedAt,
            progress = 0L,
        )
}
