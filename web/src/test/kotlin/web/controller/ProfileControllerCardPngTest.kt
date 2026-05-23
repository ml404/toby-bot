package web.controller

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.core.user.OAuth2User
import web.profile.ProfileCardData
import web.profile.ProfileCardRenderer
import web.service.ProfileCardAggregator
import web.service.ProfileWebService
import java.time.Instant

/**
 * Unit-level tests for the `GET /profile/{guildId}/{discordId}/card.png`
 * endpoint. The controller resolves Guild + Member via JDA itself (the
 * aggregator no longer takes JDA, by design — see [ProfileCardAggregator]),
 * so these tests also pin the 404 paths for missing-guild / missing-member.
 */
class ProfileControllerCardPngTest {

    private val guildId = 42L
    private val targetDiscordId = 1234L
    private val viewerDiscordId = 999L

    private lateinit var profileWebService: ProfileWebService
    private lateinit var profileCardAggregator: ProfileCardAggregator
    private lateinit var profileCardRenderer: ProfileCardRenderer
    private lateinit var jda: JDA
    private lateinit var user: OAuth2User
    private lateinit var controller: ProfileController

    @BeforeEach
    fun setUp() {
        profileWebService = mockk(relaxed = true)
        profileCardAggregator = mockk(relaxed = true)
        profileCardRenderer = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        controller = ProfileController(profileWebService, profileCardAggregator, profileCardRenderer, jda)
        user = mockk {
            every { getAttribute<String>("id") } returns viewerDiscordId.toString()
            every { getAttribute<String>("username") } returns "viewer"
        }
    }

    @Test
    fun `returns 401 when caller is not signed in`() {
        val anonymous = mockk<OAuth2User> { every { getAttribute<String>("id") } returns null }
        val response = controller.cardPng(guildId, targetDiscordId, anonymous)
        assertTrue(response.statusCode.value() == 401, "expected 401, got ${response.statusCode}")
    }

    @Test
    fun `returns 403 when caller is not a guild member`() {
        every { profileWebService.isMember(viewerDiscordId, guildId) } returns false
        val response = controller.cardPng(guildId, targetDiscordId, user)
        assertTrue(response.statusCode.value() == 403, "expected 403, got ${response.statusCode}")
    }

    @Test
    fun `returns 404 when the bot cannot see the guild`() {
        every { profileWebService.isMember(viewerDiscordId, guildId) } returns true
        every { jda.getGuildById(guildId) } returns null
        val response = controller.cardPng(guildId, targetDiscordId, user)
        assertTrue(response.statusCode.value() == 404, "expected 404, got ${response.statusCode}")
    }

    @Test
    fun `returns 404 when the target user is not in the guild`() {
        every { profileWebService.isMember(viewerDiscordId, guildId) } returns true
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(targetDiscordId) } returns null
        val response = controller.cardPng(guildId, targetDiscordId, user)
        assertTrue(response.statusCode.value() == 404, "expected 404, got ${response.statusCode}")
    }

    @Test
    fun `returns 200 image png with cache-control on the happy path`() {
        every { profileWebService.isMember(viewerDiscordId, guildId) } returns true
        val guild = mockk<Guild>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(targetDiscordId) } returns member
        every { profileCardAggregator.build(guild, member) } returns sampleData()
        val pngStub = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100)
        every { profileCardRenderer.renderPng(any()) } returns pngStub

        val response = controller.cardPng(guildId, targetDiscordId, user)
        assertTrue(response.statusCode.is2xxSuccessful, "expected 2xx, got ${response.statusCode}")
        assertTrue(response.body!!.isNotEmpty(), "expected non-empty body")
        assertTrue(
            response.headers.contentType?.toString()?.startsWith("image/png") == true,
            "expected image/png content type, got ${response.headers.contentType}",
        )
        val cc = response.headers.getFirst(HttpHeaders.CACHE_CONTROL).orEmpty()
        assertTrue(cc.contains("max-age=60"), "expected max-age=60 in cache-control, got $cc")
    }

    private fun sampleData() = ProfileCardData(
        avatarUrl = "https://avatar/1.png",
        displayName = "Alice",
        guildName = "Test Guild",
        level = 5,
        xpIntoLevel = 100,
        xpForNextLevel = 250,
        totalXp = 500,
        socialCredit = 1_000,
        equippedTitle = null,
        recentAchievements = listOf(
            ProfileCardData.AchievementSnapshot("🎲", "First Roll", Instant.now()),
        ),
    )
}
