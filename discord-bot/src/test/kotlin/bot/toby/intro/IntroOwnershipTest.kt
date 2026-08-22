package bot.toby.intro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Intro ids are `guildId_discordId_index`, so who may act on one is readable
 * straight off the id. That matters because the id arrives from the client:
 * the menus only ever *offer* intros the caller may touch, but a crafted
 * component or modal submission can name any id at all.
 *
 * [IntroOwnership.ownedBy] is the check behind editing and deleting somebody's
 * intro and had no test of its own.
 */
class IntroOwnershipTest {

    @Test
    fun `an id belongs to the member whose id it carries`() {
        assertTrue(IntroOwnership.ownedBy("7_99_1", guildId = 7L, discordId = 99L))
    }

    @Test
    fun `a crafted id cannot reach another member's intro`() {
        assertFalse(IntroOwnership.ownedBy("7_100_1", guildId = 7L, discordId = 99L))
    }

    @Test
    fun `a crafted id cannot reach across into another server`() {
        assertFalse(IntroOwnership.ownedBy("8_99_1", guildId = 7L, discordId = 99L))
        assertFalse(IntroOwnership.inGuild("8_99_1", guildId = 7L))
    }

    @Test
    fun `a prefix that merely starts the same is not a match`() {
        // Guild 7 versus guild 77, member 9 versus member 99 — the separator
        // is what stops one being read as the other.
        assertFalse(IntroOwnership.ownedBy("77_99_1", guildId = 7L, discordId = 99L))
        assertFalse(IntroOwnership.ownedBy("7_991_1", guildId = 7L, discordId = 99L))
        assertFalse(IntroOwnership.inGuild("77_99_1", guildId = 7L))
    }

    @Test
    fun `a missing id owns nothing and belongs nowhere`() {
        assertFalse(IntroOwnership.ownedBy(null, guildId = 7L, discordId = 99L))
        assertFalse(IntroOwnership.inGuild(null, guildId = 7L))
    }

    @Test
    fun `anyone in the server passes the weaker check`() {
        // The View intros menu lets any member play any member's intro, so
        // there is no owner to compare against — only the server.
        assertTrue(IntroOwnership.inGuild("7_100_1", guildId = 7L))
    }

    @Test
    fun `the server and member are readable off an id`() {
        assertEquals(7L, IntroOwnership.guildIdOf("7_99_1"))
        assertEquals(99L, IntroOwnership.discordIdOf("7_99_1"))
    }

    @Test
    fun `an unreadable id yields neither, rather than a wrong one`() {
        // These feed an outage notice and a credit payout; guessing would
        // send both to the wrong place.
        assertNull(IntroOwnership.guildIdOf("nonsense"))
        assertNull(IntroOwnership.discordIdOf("nonsense"))
        assertNull(IntroOwnership.guildIdOf(null))
        assertNull(IntroOwnership.discordIdOf(null))
        assertNull(IntroOwnership.discordIdOf("7"))
        assertNull(IntroOwnership.discordIdOf("7_notanid_1"))
    }
}
