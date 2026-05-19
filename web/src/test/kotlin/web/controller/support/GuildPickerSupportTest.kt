package web.controller.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GuildPickerSupportTest {

    @Test
    fun `picker renders when the user has no viewable guilds`() {
        val result = GuildPickerSupport.resolveRedirect(
            guildIds = emptyList(),
            cookieGuildId = null,
            pick = false,
        ) { "/casino/$it" }
        assertNull(result)
    }

    @Test
    fun `picker renders when pick=true overrides any deep-link`() {
        val result = GuildPickerSupport.resolveRedirect(
            guildIds = listOf(1L, 2L, 3L),
            cookieGuildId = 2L,
            pick = true,
        ) { "/duel/$it" }
        assertNull(result)
    }

    @Test
    fun `single viewable guild deep-links straight to that guild`() {
        val result = GuildPickerSupport.resolveRedirect(
            guildIds = listOf(99L),
            cookieGuildId = null,
            pick = false,
        ) { "/economy/$it" }
        assertEquals("redirect:/economy/99", result)
    }

    @Test
    fun `cookie hint wins over multi-guild ambiguity when it's still viewable`() {
        val result = GuildPickerSupport.resolveRedirect(
            guildIds = listOf(1L, 2L, 3L),
            cookieGuildId = 2L,
            pick = false,
        ) { "/leaderboard/$it" }
        assertEquals("redirect:/leaderboard/2", result)
    }

    @Test
    fun `stale cookie value falls through to the multi-guild picker`() {
        // Cookie points at a guild the user no longer shares.
        val result = GuildPickerSupport.resolveRedirect(
            guildIds = listOf(1L, 2L),
            cookieGuildId = 999L,
            pick = false,
        ) { "/excuses/$it" }
        assertNull(result)
    }

    @Test
    fun `multi-guild without cookie hint renders the picker`() {
        val result = GuildPickerSupport.resolveRedirect(
            guildIds = listOf(1L, 2L),
            cookieGuildId = null,
            pick = false,
        ) { "/duel/$it" }
        assertNull(result)
    }

    @Test
    fun `redirectFor lambda is invoked only when a guild is resolved`() {
        var calls = 0
        GuildPickerSupport.resolveRedirect(
            guildIds = emptyList(),
            cookieGuildId = null,
            pick = false,
        ) { calls++; "/never/$it" }
        assertEquals(0, calls)

        GuildPickerSupport.resolveRedirect(
            guildIds = listOf(7L),
            cookieGuildId = null,
            pick = false,
        ) { calls++; "/called/$it" }
        assertEquals(1, calls)
    }

    @Test
    fun `redirectFor lambda may build arbitrary URL shapes`() {
        val result = GuildPickerSupport.resolveRedirect(
            guildIds = listOf(42L),
            cookieGuildId = null,
            pick = false,
        ) { "/casino/$it/lottery" }
        assertEquals("redirect:/casino/42/lottery", result)
    }
}
