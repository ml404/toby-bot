package bot.toby.welcome

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Placeholder-substitution unit tests for [WelcomeMessageRenderer].
 * Pure-function tests — no JDA action side effects, no DB. Together
 * with [bot.toby.handler.WelcomeAndAutoRoleHandlerTest] (which verifies
 * the listener calls the renderer, picks the right channel, and posts
 * an embed) these pin the announcement pipeline end-to-end.
 */
class WelcomeMessageRendererTest {

    private lateinit var guild: Guild
    private lateinit var user: User
    private lateinit var humanMember: Member
    private lateinit var botMember: Member

    @BeforeEach
    fun setup() {
        guild = mockk(relaxed = true)
        user = mockk(relaxed = true)
        every { guild.name } returns "My Cool Guild"
        every { user.asMention } returns "<@123>"
        every { user.name } returns "alice"

        humanMember = mockk(relaxed = true)
        botMember = mockk(relaxed = true)
        val humanUser = mockk<User>(relaxed = true).also { every { it.isBot } returns false }
        val botUser = mockk<User>(relaxed = true).also { every { it.isBot } returns true }
        every { humanMember.user } returns humanUser
        every { botMember.user } returns botUser
        // Three humans + one bot — placeholder reads "3"
        every { guild.members } returns listOf(humanMember, humanMember, humanMember, botMember)
    }

    // ---- substitution ----

    @Test
    fun `render substitutes all four placeholders`() {
        val template = "Hi {user} aka {user.name}, welcome to {server} (member #{membercount})"
        val out = WelcomeMessageRenderer.render(
            template, WelcomeMessageRenderer.DEFAULT_WELCOME, guild, user, memberDisplayName = "Alice",
        )
        assertEquals("Hi <@123> aka Alice, welcome to My Cool Guild (member #3)", out)
    }

    @Test
    fun `render falls back to user name when member display name is blank`() {
        val out = WelcomeMessageRenderer.render(
            template = "Hi {user.name}",
            default = WelcomeMessageRenderer.DEFAULT_WELCOME,
            guild = guild,
            user = user,
            memberDisplayName = "   ",
        )
        assertEquals("Hi alice", out)
    }

    @Test
    fun `render falls back to user name when member display name is null`() {
        val out = WelcomeMessageRenderer.render(
            template = "Hi {user.name}",
            default = WelcomeMessageRenderer.DEFAULT_WELCOME,
            guild = guild,
            user = user,
            memberDisplayName = null,
        )
        assertEquals("Hi alice", out)
    }

    @Test
    fun `render uses default template when caller supplies blank text`() {
        val out = WelcomeMessageRenderer.render(
            template = "",
            default = WelcomeMessageRenderer.DEFAULT_WELCOME,
            guild = guild,
            user = user,
            memberDisplayName = "Alice",
        )
        // Default template expands {user}, {server}, {membercount}
        assertTrue(out.contains("<@123>"), "expected user mention in default template: $out")
        assertTrue(out.contains("My Cool Guild"), "expected guild name in default template: $out")
        assertTrue(out.contains("3"), "expected member count in default template: $out")
    }

    @Test
    fun `render uses default template when caller supplies null text`() {
        val out = WelcomeMessageRenderer.render(
            template = null,
            default = WelcomeMessageRenderer.DEFAULT_WELCOME,
            guild = guild,
            user = user,
        )
        assertTrue(out.contains("My Cool Guild"), out)
    }

    @Test
    fun `render passes unknown placeholders through verbatim`() {
        val out = WelcomeMessageRenderer.render(
            template = "Hi {usernme} (typo) — {user.name}",
            default = WelcomeMessageRenderer.DEFAULT_WELCOME,
            guild = guild,
            user = user,
            memberDisplayName = "Alice",
        )
        // `{usernme}` is not a known token — copied through so admins see
        // the typo instead of silent removal.
        assertTrue(out.contains("{usernme}"), out)
        assertTrue(out.contains("Alice"), out)
    }

    @Test
    fun `render counts only non-bot members`() {
        val out = WelcomeMessageRenderer.render(
            template = "{membercount}",
            default = WelcomeMessageRenderer.DEFAULT_WELCOME,
            guild = guild,
            user = user,
            memberDisplayName = "Alice",
        )
        // 3 humans, 1 bot → 3
        assertEquals("3", out)
    }

    @Test
    fun `render substitutes user_name before user so mention wins for plain user token`() {
        // Substitution order matters: `{user.name}` and `{user}` share a
        // prefix. If `{user}` were replaced first it would partially
        // consume `{user.name}` and leave a literal `.name` artefact.
        val out = WelcomeMessageRenderer.render(
            template = "{user} {user.name}",
            default = WelcomeMessageRenderer.DEFAULT_WELCOME,
            guild = guild,
            user = user,
            memberDisplayName = "Alice",
        )
        assertEquals("<@123> Alice", out)
        assertFalse(out.contains(".name"), "user_name token must be consumed before user: $out")
    }

    // ---- default selection ----

    @Test
    fun `render uses the supplied default for goodbye when template is blank`() {
        val out = WelcomeMessageRenderer.render(
            template = null,
            default = WelcomeMessageRenderer.DEFAULT_GOODBYE,
            guild = guild,
            user = user,
            memberDisplayName = null,
        )
        // DEFAULT_GOODBYE references {user.name} and {server}
        assertTrue(out.contains("alice"), out)
        assertTrue(out.contains("My Cool Guild"), out)
    }

    @Test
    fun `render with goodbye default produces different text than welcome default`() {
        // Same fixture, blank template, but the welcome and goodbye defaults
        // must produce different text — otherwise admins who opt into both
        // get duplicate messages on a quick join-then-leave.
        val welcome = WelcomeMessageRenderer.render(null, WelcomeMessageRenderer.DEFAULT_WELCOME, guild, user, "Alice")
        val goodbye = WelcomeMessageRenderer.render(null, WelcomeMessageRenderer.DEFAULT_GOODBYE, guild, user, "Alice")
        assertFalse(welcome == goodbye, "welcome and goodbye defaults must differ")
    }
}
