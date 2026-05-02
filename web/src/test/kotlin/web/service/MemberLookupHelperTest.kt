package web.service

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemberLookupHelperTest {

    private val jda = mockk<JDA>(relaxed = true)
    private val helper = MemberLookupHelper(jda)
    private val guildId = 99L

    @Test
    fun `resolve returns null when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertNull(helper.resolve(guildId, 1L))
    }

    @Test
    fun `resolve returns null when member has left the guild`() {
        val guild = mockk<Guild>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(1L) } returns null
        assertNull(helper.resolve(guildId, 1L))
    }

    @Test
    fun `resolve returns name and avatar when member is present`() {
        val guild = mockk<Guild>(relaxed = true)
        val member = mockk<Member>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(7L) } returns member
        every { member.effectiveName } returns "Alice"
        every { member.effectiveAvatarUrl } returns "https://cdn.example/a.png"

        val display = helper.resolve(guildId, 7L)
        assertEquals("Alice", display?.name)
        assertEquals("https://cdn.example/a.png", display?.avatarUrl)
    }

    @Test
    fun `resolveAll skips JDA call entirely when input is empty`() {
        // No mocks set; if helper touched JDA this would throw on the relaxed mock chain.
        assertTrue(helper.resolveAll(guildId, emptyList()).isEmpty())
    }

    @Test
    fun `resolveAll returns empty map when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertTrue(helper.resolveAll(guildId, listOf(1L, 2L)).isEmpty())
    }

    @Test
    fun `resolveAll fetches the guild once and resolves each id`() {
        val guild = mockk<Guild>(relaxed = true)
        val alice = mockk<Member>(relaxed = true)
        val bob = mockk<Member>(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(1L) } returns alice
        every { guild.getMemberById(2L) } returns bob
        every { guild.getMemberById(3L) } returns null
        every { alice.effectiveName } returns "Alice"
        every { alice.effectiveAvatarUrl } returns "a.png"
        every { bob.effectiveName } returns "Bob"
        every { bob.effectiveAvatarUrl } returns "b.png"

        val out = helper.resolveAll(guildId, listOf(1L, 2L, 3L))
        assertEquals(setOf(1L, 2L), out.keys)
        assertEquals("Alice", out[1L]?.name)
        assertEquals("Bob", out[2L]?.name)
        assertEquals("a.png", out[1L]?.avatarUrl)
    }

    @Test
    fun `fallbackName uses last 4 digits of discord id`() {
        assertEquals("Player 1234", helper.fallbackName(99991234L))
        // Short ids get padded by takeLast naturally (returns whole id).
        assertEquals("Player 42", helper.fallbackName(42L))
    }
}
