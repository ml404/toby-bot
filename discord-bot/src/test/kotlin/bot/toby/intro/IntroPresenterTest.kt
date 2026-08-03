package bot.toby.intro

import database.dto.music.MusicDto
import database.dto.user.UserDto
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
