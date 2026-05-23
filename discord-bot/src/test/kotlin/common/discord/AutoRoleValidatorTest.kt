package common.discord

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.SelfMember
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AutoRoleValidator]. Pinned independently of the three
 * consumers (slash command, web service, listener) so a wording change
 * here forces every consumer's test to acknowledge the new copy.
 */
class AutoRoleValidatorTest {

    private lateinit var role: Role
    private lateinit var selfMember: SelfMember

    @BeforeEach
    fun setUp() {
        role = mockk<Role>(relaxed = true).also {
            every { it.id } returns "100"
            every { it.idLong } returns 100L
            every { it.name } returns "Member"
            every { it.asMention } returns "<@&100>"
            every { it.isPublicRole } returns false
            every { it.isManaged } returns false
        }
        selfMember = mockk<SelfMember>(relaxed = true).also {
            every { it.canInteract(role) } returns true
        }
    }

    @Test
    fun `valid role returns null`() {
        assertNull(AutoRoleValidator.validate(role, selfMember))
    }

    @Test
    fun `everyone role is rejected with a specific message`() {
        every { role.isPublicRole } returns true
        assertEquals("Cannot auto-assign @everyone.", AutoRoleValidator.validate(role, selfMember))
    }

    @Test
    fun `managed role is rejected with the role name in the message`() {
        every { role.isManaged } returns true
        val msg = AutoRoleValidator.validate(role, selfMember)
        assertEquals(
            "Member is managed by an integration and can't be assigned by the bot.",
            msg,
        )
    }

    @Test
    fun `role above the bot is rejected with a position-fix hint`() {
        every { selfMember.canInteract(role) } returns false
        val msg = AutoRoleValidator.validate(role, selfMember)
        assertEquals(
            "Member sits above TobyBot's role — move TobyBot's role higher to allow assignment.",
            msg,
        )
    }

    @Test
    fun `everyone is rejected even when also managed`() {
        // Belt-and-braces: if a role somehow matches multiple failure
        // modes, the everyone check fires first so admins see the
        // most-relevant explanation.
        every { role.isPublicRole } returns true
        every { role.isManaged } returns true
        assertEquals("Cannot auto-assign @everyone.", AutoRoleValidator.validate(role, selfMember))
    }
}
