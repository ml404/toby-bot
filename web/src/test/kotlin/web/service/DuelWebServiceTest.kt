package web.service

import database.duel.PendingDuelRegistry
import database.dto.UserDto
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class DuelWebServiceTest {

    private val opponentId = 200L
    private val guildId = 42L

    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var userService: UserService
    private lateinit var service: DuelWebService

    @BeforeEach
    fun setup() {
        pendingDuelRegistry = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        service = DuelWebService(pendingDuelRegistry, userService)
    }

    @Test
    fun `pendingForOpponent projects registry entries to view DTOs`() {
        every { pendingDuelRegistry.pendingForOpponent(opponentId, guildId) } returns listOf(
            PendingDuelRegistry.PendingDuel(
                id = 1L, guildId = guildId,
                initiatorDiscordId = 100L, opponentDiscordId = opponentId,
                stake = 50L, createdAt = Instant.now()
            ),
            PendingDuelRegistry.PendingDuel(
                id = 2L, guildId = guildId,
                initiatorDiscordId = 101L, opponentDiscordId = opponentId,
                stake = 75L, createdAt = Instant.now()
            ),
        )

        val rows = service.pendingForOpponent(opponentId, guildId)

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.opponentDiscordId == opponentId })
        assertEquals(50L, rows[0].stake)
        assertEquals(75L, rows[1].stake)
    }

    @Test
    fun `ensureOpponent returns existing user without creating`() {
        val existing = UserDto(opponentId, guildId)
        every { userService.getUserById(opponentId, guildId) } returns existing

        val result = service.ensureOpponent(opponentId, guildId)

        assertEquals(existing, result)
        verify(exactly = 0) { userService.createNewUser(any()) }
    }

    @Test
    fun `ensureOpponent lazily creates a row when none exists`() {
        every { userService.getUserById(opponentId, guildId) } returns null
        every { userService.createNewUser(any()) } answers { firstArg() }

        val result = service.ensureOpponent(opponentId, guildId)

        assertEquals(opponentId, result.discordId)
        assertEquals(guildId, result.guildId)
    }
}
