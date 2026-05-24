package database.service.impl

import database.dto.guild.AutoRoleDto
import database.persistence.guild.AutoRolePersistence
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import database.service.guild.impl.DefaultAutoRoleService

/**
 * Thin-delegate sanity tests for [DefaultAutoRoleService]. The service
 * has no logic beyond forwarding to [AutoRolePersistence]; these
 * regression tests pin the wiring so a refactor that swaps the
 * persistence dependency or accidentally drops a method gets caught.
 */
class DefaultAutoRoleServiceTest {

    private lateinit var persistence: AutoRolePersistence
    private lateinit var service: DefaultAutoRoleService

    @BeforeEach
    fun setUp() {
        persistence = mockk(relaxed = true)
        service = DefaultAutoRoleService(persistence)
    }

    @Test
    fun `listForGuild delegates to persistence`() {
        val expected = listOf(AutoRoleDto(guildId = 1L, roleId = 7L))
        every { persistence.listForGuild(1L) } returns expected
        assertSame(expected, service.listForGuild(1L))
        verify(exactly = 1) { persistence.listForGuild(1L) }
    }

    @Test
    fun `add delegates to persistence`() {
        val expected = AutoRoleDto(guildId = 1L, roleId = 7L)
        every { persistence.add(1L, 7L) } returns expected
        assertSame(expected, service.add(1L, 7L))
        verify(exactly = 1) { persistence.add(1L, 7L) }
    }

    @Test
    fun `delete delegates to persistence`() {
        service.delete(1L, 7L)
        verify(exactly = 1) { persistence.delete(1L, 7L) }
    }

    @Test
    fun `listForGuild empty returns empty list without error`() {
        every { persistence.listForGuild(9999L) } returns emptyList()
        assertEquals(emptyList<AutoRoleDto>(), service.listForGuild(9999L))
    }
}
