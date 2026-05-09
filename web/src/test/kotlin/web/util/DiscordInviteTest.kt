package web.util

import net.dv8tion.jda.api.Permission
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the granular OAuth invite-URL bitmask. The whole point of
 * spelling out the required permissions instead of `permissions=8`
 * (Administrator) is so a server owner sees an explicit list during
 * install and so a later "tighten the bot's role" pass keeps the
 * permissions the bot's UX actually needs.
 *
 * Each assertion below maps to a feature: removing one of these
 * implies removing that feature, and is intentional rather than
 * accidental — change this test alongside the production change so
 * the diff makes the implication obvious.
 */
internal class DiscordInviteTest {

    @Test
    fun `bitmask is non-administrator (we explicitly opted out of permissions=8)`() {
        // Administrator is `1L shl 3 = 8`. The whole granular invite
        // exists to NOT use it — anyone reading the install dialog
        // should see itemised permissions instead of "Administrator".
        assertFalse(
            (DiscordInvite.permissionsBitmask and Permission.ADMINISTRATOR.rawValue) != 0L,
            "invite bitmask must not include Administrator (use the granular set instead)",
        )
    }

    @Test
    fun `every UX feature has its required Discord permission in the bitmask`() {
        // Spell each feature → permission mapping out so the failure
        // message names exactly which feature breaks if the bitmask
        // ever drops a flag. New features that need a new permission
        // add a line here AND in DiscordInvite.REQUIRED_PERMISSIONS.
        assertAll(
            { mustHave(Permission.VIEW_CHANNEL, "see any channel the bot is meant to operate in") },
            { mustHave(Permission.MESSAGE_SEND, "post slash-command responses + result lines") },
            { mustHave(Permission.MESSAGE_HISTORY, "edit prior messages (active anti-autoclick embed)") },
            { mustHave(Permission.MESSAGE_EMBED_LINKS, "rich embeds (jackpot banner, mod-log session, etc.)") },
            { mustHave(Permission.MESSAGE_ATTACH_FILES, "audio uploads / meme images") },
            { mustHave(Permission.MESSAGE_EXT_EMOJI, "cross-server emoji in jackpot / casino flourishes") },
            { mustHave(Permission.MESSAGE_ADD_REACTION, "emoji-reaction polls") },
            { mustHave(Permission.KICK_MEMBERS, "moderation kick action") },
            { mustHave(Permission.MESSAGE_MANAGE, "configurable message-delete delay") },
            { mustHave(Permission.MANAGE_CHANNEL, "create read-only / admin-only mod-log channels") },
            { mustHave(Permission.MANAGE_ROLES, "set channel-level permission overrides + assign equipped-title roles") },
            { mustHave(Permission.VOICE_CONNECT, "join voice for music + intro playback") },
            { mustHave(Permission.VOICE_SPEAK, "play audio in voice") },
            { mustHave(Permission.VOICE_USE_VAD, "voice-activity detection (vs push-to-talk-only servers)") },
            { mustHave(Permission.VOICE_MUTE_OTHERS, "moderation voice mute action") },
            { mustHave(Permission.VOICE_MOVE_OTHERS, "moderation voice move action") },
        )
    }

    @Test
    fun `urlFor builds a well-formed Discord OAuth URL`() {
        val url = DiscordInvite.urlFor("12345")

        assertTrue(url.startsWith("https://discord.com/api/oauth2/authorize?"))
        assertTrue(url.contains("client_id=12345"))
        assertTrue(url.contains("permissions=${DiscordInvite.permissionsBitmask}"))
        assertTrue(url.contains("scope=bot%20applications.commands"))
    }

    @Test
    fun `bitmask matches exactly the sum of declared permission flags`() {
        // Sanity check — derives the expected sum from the same
        // public set the production code uses, so a typo / accidental
        // duplicate / accidental ADMINISTRATOR can't slip through.
        val expected = listOf(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_ATTACH_FILES,
            Permission.MESSAGE_EXT_EMOJI,
            Permission.MESSAGE_ADD_REACTION,
            Permission.KICK_MEMBERS,
            Permission.MESSAGE_MANAGE,
            Permission.MANAGE_CHANNEL,
            Permission.MANAGE_ROLES,
            Permission.VOICE_CONNECT,
            Permission.VOICE_SPEAK,
            Permission.VOICE_USE_VAD,
            Permission.VOICE_MUTE_OTHERS,
            Permission.VOICE_MOVE_OTHERS,
        ).fold(0L) { acc, p -> acc or p.rawValue }

        assertEquals(
            expected, DiscordInvite.permissionsBitmask,
            "If you intentionally added/removed a permission, update both this test " +
                "and DiscordInvite.REQUIRED_PERMISSIONS so the change is reviewed."
        )
    }

    private fun mustHave(p: Permission, why: String) {
        assertTrue(
            (DiscordInvite.permissionsBitmask and p.rawValue) != 0L,
            "Discord invite bitmask is missing $p — needed for: $why",
        )
    }
}
