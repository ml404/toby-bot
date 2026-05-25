package web.service

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModerationAuthorizerTest {

    private lateinit var jda: JDA
    private lateinit var introWebService: IntroWebService
    private lateinit var authorizer: ModerationAuthorizer

    private val discordId = 42L
    private val guildId = 100L

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        authorizer = ModerationAuthorizer(jda, introWebService)
    }

    @Test
    fun `guild unknown to JDA - denied without ever consulting super-user lookup`() {
        // Bot not in this guild → no member context exists. Must be a hard
        // deny, otherwise an unknown guildId would short-circuit through
        // the super-user override.
        every { jda.getGuildById(guildId) } returns null

        assertFalse(authorizer.canModerate(discordId, guildId))
    }

    @Test
    fun `owner can moderate even if not a super-user`() {
        val member = mockk<Member> { every { isOwner } returns true }
        val guild = mockk<Guild> { every { getMemberById(discordId) } returns member }
        every { jda.getGuildById(guildId) } returns guild
        every { introWebService.isSuperUser(discordId, guildId) } returns false

        assertTrue(authorizer.canModerate(discordId, guildId))
    }

    @Test
    fun `non-owner super-user can moderate`() {
        val member = mockk<Member> { every { isOwner } returns false }
        val guild = mockk<Guild> { every { getMemberById(discordId) } returns member }
        every { jda.getGuildById(guildId) } returns guild
        every { introWebService.isSuperUser(discordId, guildId) } returns true

        assertTrue(authorizer.canModerate(discordId, guildId))
    }

    @Test
    fun `non-owner non-super-user is denied`() {
        val member = mockk<Member> { every { isOwner } returns false }
        val guild = mockk<Guild> { every { getMemberById(discordId) } returns member }
        every { jda.getGuildById(guildId) } returns guild
        every { introWebService.isSuperUser(discordId, guildId) } returns false

        assertFalse(authorizer.canModerate(discordId, guildId))
    }

    @Test
    fun `unknown member still falls back to the super-user override`() {
        // User isn't a member of the guild (could be a Discord-side
        // de-sync) — they can still gain moderation via the super-user
        // override, since that flag lives in tobybot's own DB.
        val guild = mockk<Guild> { every { getMemberById(discordId) } returns null }
        every { jda.getGuildById(guildId) } returns guild
        every { introWebService.isSuperUser(discordId, guildId) } returns true

        assertTrue(authorizer.canModerate(discordId, guildId))
    }
}
