package web.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.imageio.ImageIO

/**
 * Output-shape tests for [ProfileCardRenderer]. No pixel-level snapshot
 * assertions — font hinting + anti-aliasing differ between JDK builds,
 * which would produce flaky byte-level diffs. Instead pin:
 *  - The PNG magic bytes (`89 50 4E 45`) so the byte stream is parseable.
 *  - The decoded dimensions (900 × 400).
 *  - Non-trivial body size (> 1 KB) so an empty / 1×1 placeholder regression
 *    surfaces loudly.
 *
 * The edge-case tests (long name, empty achievements, level extremes,
 * null title) all assert the same invariants — the renderer must never
 * throw or produce a bad PNG, regardless of input shape.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileCardRendererTest {

    private lateinit var renderer: ProfileCardRenderer

    @BeforeAll
    fun setUp() {
        renderer = ProfileCardRenderer()
    }

    @Test
    fun `renders a 900x400 PNG for a complete profile`() {
        val png = renderer.renderPng(sample())
        assertPngShape(png, 900, 400)
        assertTrue(png.size > 1000, "expected non-trivial PNG body, got ${png.size} bytes")
    }

    @Test
    fun `renders without a title`() {
        val png = renderer.renderPng(sample(equippedTitle = null))
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `renders with no achievements`() {
        val png = renderer.renderPng(sample(recentAchievements = emptyList()))
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `renders with a single-digit level`() {
        val png = renderer.renderPng(sample(level = 1, xpIntoLevel = 0))
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `renders with a triple-digit level (diamond tier)`() {
        val png = renderer.renderPng(sample(level = 150))
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `truncates a long display name instead of overflowing`() {
        // Names of any length must not throw or break the layout — the
        // renderer is responsible for ellipsising. Just assert it stays
        // a valid PNG of the correct size.
        val absurdlyLongName = "A".repeat(500)
        val png = renderer.renderPng(sample(displayName = absurdlyLongName))
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `renders with an unparseable title color hex`() {
        // Falls back to a default accent — must not throw on bad input.
        val png = renderer.renderPng(
            sample(equippedTitle = ProfileCardData.TitleSnapshot(label = "Tester", colorHex = "not-a-color"))
        )
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `renders when xpForNextLevel is zero`() {
        // Defensive — should never hit this in prod (every level has positive
        // XP requirement), but guard against div-by-zero regressions.
        val png = renderer.renderPng(sample(xpIntoLevel = 0, xpForNextLevel = 0))
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `renders with an active streak badge`() {
        val png = renderer.renderPng(sample(streakDays = 12, streakActive = true))
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `renders with an inactive (lapsed) streak - badge suppressed`() {
        val png = renderer.renderPng(sample(streakDays = 30, streakActive = false))
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `renders with a large multi-digit streak without overflowing`() {
        val png = renderer.renderPng(sample(streakDays = 365, streakActive = true))
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `avatar fetches are restricted to https urls`() {
        assertTrue(ProfileCardRenderer.isFetchableAvatarUrl("https://cdn.discordapp.com/avatars/1/a.png"))
        assertFalse(ProfileCardRenderer.isFetchableAvatarUrl("http://cdn.discordapp.com/avatars/1/a.png"))
        assertFalse(ProfileCardRenderer.isFetchableAvatarUrl("file:///etc/passwd"))
        assertFalse(ProfileCardRenderer.isFetchableAvatarUrl("not a url"))
    }

    @Test
    fun `renders a grey-disc card without fetching when the avatar URL is not https`() {
        val png = renderer.renderPng(sample(avatarUrl = "file:///etc/passwd"))
        assertPngShape(png, 900, 400)
    }

    @Test
    fun `renders gracefully when the avatar URL is unreachable`() {
        // The renderer catches ImageIO / network errors and substitutes a
        // grey disc. Asserts that the substitution path produces a valid
        // PNG end-to-end.
        val png = renderer.renderPng(sample(avatarUrl = "https://invalid.localhost:9/none.png"))
        assertPngShape(png, 900, 400)
    }

    private fun sample(
        avatarUrl: String = "https://invalid.localhost:9/avatar.png",
        displayName: String = "Alice",
        level: Int = 12,
        xpIntoLevel: Long = 420,
        xpForNextLevel: Long = 1100,
        equippedTitle: ProfileCardData.TitleSnapshot? = ProfileCardData.TitleSnapshot("🌱 Sprout", "#57F287"),
        recentAchievements: List<ProfileCardData.AchievementSnapshot> = listOf(
            ProfileCardData.AchievementSnapshot("🎲", "First Roll", Instant.now()),
            ProfileCardData.AchievementSnapshot("🔥", "5-day streak", Instant.now()),
            ProfileCardData.AchievementSnapshot("🏆", "Big winner", Instant.now()),
        ),
        streakDays: Int = 7,
        streakActive: Boolean = true,
    ) = ProfileCardData(
        avatarUrl = avatarUrl,
        displayName = displayName,
        guildName = "Test Guild",
        level = level,
        xpIntoLevel = xpIntoLevel,
        xpForNextLevel = xpForNextLevel,
        totalXp = 12_345,
        socialCredit = 8_400,
        equippedTitle = equippedTitle,
        recentAchievements = recentAchievements,
        streakDays = streakDays,
        streakActive = streakActive,
    )

    private fun assertPngShape(png: ByteArray, expectedWidth: Int, expectedHeight: Int) {
        // PNG signature is `89 50 4E 47 0D 0A 1A 0A`; check the first four
        // bytes which are the diagnostic magic for "this is a PNG".
        assertTrue(
            png.size >= 4 &&
                png[0] == 0x89.toByte() &&
                png[1] == 0x50.toByte() &&
                png[2] == 0x4E.toByte() &&
                png[3] == 0x47.toByte(),
            "byte stream is not a PNG (first 4 bytes: ${png.take(4).joinToString { "%02x".format(it) }})"
        )
        val img = ByteArrayInputStream(png).use { ImageIO.read(it) }
        requireNotNull(img) { "ImageIO failed to decode the rendered bytes" }
        assertEquals(expectedWidth, img.width, "rendered width must be $expectedWidth")
        assertEquals(expectedHeight, img.height, "rendered height must be $expectedHeight")
    }
}
