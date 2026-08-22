package bot.toby.intro

import common.intro.IntroHealth
import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class IntroPresenterTest {

    private val userDto = UserDto(discordId = 1234L, guildId = 4567L)

    private fun urlIntro(index: Int, name: String, volume: Int = 90, start: Int? = null, end: Int? = null) =
        MusicDto(userDto, index, name, volume, "https://www.youtube.com/watch?v=abc".toByteArray(), start, end)

    private fun fileIntro(index: Int, name: String) =
        MusicDto(userDto, index, name, 90, byteArrayOf(1, 2, 3))

    private fun member(name: String = "Someone", avatar: String = "https://cdn.example/avatar.png"): Member =
        mockk(relaxed = true) {
            every { effectiveName } returns name
            every { effectiveAvatarUrl } returns avatar
        }

    @Test
    fun `options are ordered by slot, not insertion order`() {
        val options = IntroPresenter.selectOptions(listOf(urlIntro(3, "third"), urlIntro(1, "first"), urlIntro(2, "second")))
        assertEquals(listOf("first", "second", "third"), options.map { it.label })
    }

    @Test
    fun `option values are the intro ids so the menu handlers can look them up`() {
        val intro = urlIntro(2, "second")
        val option = IntroPresenter.selectOptions(listOf(intro)).single()
        assertEquals(intro.id, option.value)
    }

    @Test
    fun `long titles are truncated to Discord's select limit`() {
        // A real YouTube title can comfortably exceed 100 characters; building
        // the option unclamped used to throw and break the whole menu.
        val longTitle = "y".repeat(250)
        val option = IntroPresenter.selectOptions(listOf(urlIntro(1, longTitle))).single()
        assertEquals(100, option.label.length)
        assertTrue(option.label.endsWith("…"))
    }

    @Test
    fun `option description summarises slot, volume and clip`() {
        val option = IntroPresenter.selectOptions(listOf(urlIntro(2, "track", volume = 40, start = 3_000, end = 9_000)))
            .single()
        assertEquals("Slot #2 · Volume 40% · 0:03 – 0:09", option.description)
    }

    @Test
    fun `unclipped intros are described as the full track`() {
        assertEquals("Slot #1 · Volume 90% · full track", IntroPresenter.summary(urlIntro(1, "track")))
    }

    @Test
    fun `url and file intros are distinguished by their blob`() {
        assertTrue(IntroPresenter.isUrlIntro(urlIntro(1, "link")))
        assertFalse(IntroPresenter.isUrlIntro(fileIntro(1, "upload.mp3")))
        assertFalse(IntroPresenter.isUrlIntro(MusicDto(userDto, 1, "empty", 90, null)))
    }

    @Test
    fun `blank names fall back to a placeholder`() {
        assertEquals("Unknown", IntroPresenter.displayName(MusicDto(userDto, 1, "   ", 90, null)))
        assertEquals("Unknown", IntroPresenter.displayName(MusicDto(userDto, 1, null, 90, null)))
    }

    @Test
    fun `list embed has a field per intro in slot order`() {
        val embed = IntroPresenter.listEmbed(member(), listOf(urlIntro(2, "second"), fileIntro(1, "first.mp3")), 3)

        assertEquals(2, embed.fields.size)
        assertTrue(embed.fields[0].name!!.startsWith("#1 · first.mp3"))
        assertTrue(embed.fields[1].name!!.startsWith("#2 · second"))
        assertTrue(embed.fields[0].value!!.contains("Uploaded file"))
        assertTrue(embed.fields[1].value!!.contains("Link"))
        assertEquals("2/3 slots used · edit with /editintro, remove with /deleteintro", embed.footer?.text)
    }

    @Test
    fun `empty list embed points at setintro`() {
        val embed = IntroPresenter.listEmbed(member(), emptyList(), 3)
        assertTrue(embed.fields.isEmpty())
        assertTrue(embed.description!!.contains("/setintro"))
    }

    @Test
    fun `a member without a usable avatar url does not blow up the embed`() {
        // EmbedBuilder rejects non-http thumbnails; relaxed/absent avatars are common in tests.
        val embed = IntroPresenter.listEmbed(member(avatar = ""), listOf(urlIntro(1, "track")), 3)
        assertEquals(1, embed.fields.size)
    }

    @Test
    fun `dashboard button deep links to the guild intro page`() {
        val button = IntroPresenter.webDashboardButton(4567L)
        assertEquals("https://www.toby-bot.co.uk/intro/4567", button.url)
    }

    // --- broken intros ------------------------------------------------------

    @Test
    fun `a broken intro says so first in its summary`() {
        // Whatever else is true of the intro, "it isn't playing" is the thing
        // the reader is trying to find out.
        val broken = urlIntro(1, "Dead link").apply { failureCount = IntroHealth.UNHEALTHY_AFTER_FAILURES }

        assertTrue(IntroPresenter.summary(broken).startsWith("BROKEN · "), IntroPresenter.summary(broken))
    }

    @Test
    fun `an intro with one failure is not called broken`() {
        val flaky = urlIntro(1, "Occasionally slow").apply { failureCount = 1 }

        assertFalse(IntroPresenter.isBroken(flaky))
        assertFalse(IntroPresenter.summary(flaky).contains("BROKEN"))
    }

    @Test
    fun `a single failure says so rather than looking perfectly fine`() {
        // The reason is written on the very first failure. Withholding it
        // until the second left somebody who had just heard silence looking
        // at a row that described itself as healthy — and failures accrue one
        // per join, so the second could be days away.
        val flaky = urlIntro(1, "Occasionally slow").apply {
            failureCount = 1
            lastFailureReason = "This video is not available in your country"
        }

        val field = IntroPresenter.listEmbed(member(), listOf(flaky), 3).fields.single()

        assertTrue(field.value!!.contains("Didn't play last time"), field.value!!)
        assertTrue(field.value!!.contains("not available in your country"), field.value!!)
        // Still not called broken: one failure is as likely a blip as a fault.
        assertFalse(field.name!!.contains("⚠️"), field.name!!)
        assertFalse(field.value!!.contains("Not playing"), field.value!!)
    }

    @Test
    fun `an intro that has never failed says nothing about failing`() {
        val fine = urlIntro(1, "Working")

        val field = IntroPresenter.listEmbed(member(), listOf(fine), 3).fields.single()

        assertFalse(field.value!!.contains("Didn't play"), field.value!!)
        assertFalse(field.value!!.contains("Not playing"), field.value!!)
    }

    @Test
    fun `the list embed shows why a broken intro stopped working`() {
        val broken = urlIntro(1, "Dead link").apply {
            failureCount = IntroHealth.UNHEALTHY_AFTER_FAILURES
            lastFailureReason = "This video is not available in your country"
        }

        val field = IntroPresenter.listEmbed(member(), listOf(broken), 3).fields.single()

        assertTrue(field.name!!.contains("⚠️"), field.name!!)
        assertTrue(field.value!!.contains("Not playing"), field.value!!)
        assertTrue(field.value!!.contains("not available in your country"), field.value!!)
    }

    @Test
    fun `a broken intro with no recorded reason still explains itself`() {
        val broken = urlIntro(1, "Dead link").apply { failureCount = IntroHealth.UNHEALTHY_AFTER_FAILURES }

        val field = IntroPresenter.listEmbed(member(), listOf(broken), 3).fields.single()

        assertTrue(field.value!!.contains("source could not be loaded"), field.value!!)
    }

    @Test
    fun `an overlong failure reason cannot burst the embed field`() {
        val broken = urlIntro(1, "Dead link").apply {
            failureCount = IntroHealth.UNHEALTHY_AFTER_FAILURES
            lastFailureReason = "x".repeat(2000)
        }

        val field = IntroPresenter.listEmbed(member(), listOf(broken), 3).fields.single()

        // Discord's field-value cap is 1024; the reason is bounded well below it.
        assertTrue(field.value!!.length < 1024, "field value was ${field.value!!.length} chars")
    }

    @Test
    fun `every intro being broken is called out at the top of the embed`() {
        val broken = urlIntro(1, "a").apply { failureCount = IntroHealth.UNHEALTHY_AFTER_FAILURES }
        val alsoBroken = urlIntro(2, "b").apply { failureCount = IntroHealth.UNHEALTHY_AFTER_FAILURES }

        val embed = IntroPresenter.listEmbed(member(), listOf(broken, alsoBroken), 3)

        assertTrue(embed.description!!.contains("stopped loading"), embed.description!!)
    }

    @Test
    fun `switched-off intros are not reported as broken`() {
        // "Every intro is switched off" is the owner's own doing and already
        // has its own message; don't blame the sources for it.
        val off = urlIntro(1, "a").apply { enabled = false }

        val embed = IntroPresenter.listEmbed(member(), listOf(off), 3)

        assertTrue(embed.description!!.contains("switched off"), embed.description!!)
    }

    // --- play stats ---------------------------------------------------------

    @Test
    fun `an intro that has never played reports nothing rather than zero`() {
        assertNull(IntroPresenter.playSummary(urlIntro(1, "track")))
    }

    @Test
    fun `play counts appear once there is something to count`() {
        val played = urlIntro(1, "track").apply {
            playCount = 12
            lastPlayedAt = Instant.parse("2026-01-01T00:00:00Z")
        }

        val summary = IntroPresenter.playSummary(played, now = Instant.parse("2026-01-04T00:00:00Z"))!!

        assertTrue(summary.contains("Played 12×"), summary)
        assertTrue(summary.contains("3d ago"), summary)
    }

    @Test
    fun `a play count with no timestamp still reports the count`() {
        val played = urlIntro(1, "track").apply { playCount = 3 }

        assertEquals("Played 3×", IntroPresenter.playSummary(played))
    }

    @Test
    fun `relative times read the way a person would say them`() {
        val now = Instant.parse("2026-06-01T12:00:00Z")

        assertEquals("just now", IntroPresenter.relativeTime(now.minusSeconds(5), now))
        assertEquals("30m ago", IntroPresenter.relativeTime(now.minusSeconds(1800), now))
        assertEquals("5h ago", IntroPresenter.relativeTime(now.minusSeconds(5 * 3600), now))
        assertEquals("2d ago", IntroPresenter.relativeTime(now.minusSeconds(2 * 86400), now))
        assertEquals("3mo ago", IntroPresenter.relativeTime(now.minusSeconds(95L * 86400), now))
    }

    @Test
    fun `a clock skew into the future does not produce a negative age`() {
        val now = Instant.parse("2026-06-01T12:00:00Z")

        assertEquals("just now", IntroPresenter.relativeTime(now.plusSeconds(60), now))
    }
}
