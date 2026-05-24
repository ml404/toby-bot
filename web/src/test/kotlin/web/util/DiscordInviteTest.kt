package web.util

import net.dv8tion.jda.api.Permission
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the OAuth invite-URL bitmask to Administrator
 * (`permissions=8`). Administrator supersedes the granular
 * per-feature permissions, so no per-feature assertions are needed.
 */
internal class DiscordInviteTest {

    @Test
    fun `bitmask is exactly Administrator`() {
        assertEquals(
            Permission.ADMINISTRATOR.rawValue,
            DiscordInvite.permissionsBitmask,
            "invite bitmask must be Administrator (raw value 8)",
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
}
