package web.service

import database.duel.PendingDuelRegistry
import database.dto.UserDto
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class DuelWebServiceTest {

    private val opponentId = 200L
    private val guildId = 42L

    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var userService: UserService
    private lateinit var memberLookup: MemberLookupHelper
    private lateinit var service: DuelWebService

    @BeforeEach
    fun setup() {
        pendingDuelRegistry = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        memberLookup = mockk {
            every { resolveAll(any(), any()) } returns emptyMap()
            every { fallbackName(any()) } answers { "Player ${firstArg<Long>().toString().takeLast(4)}" }
        }
        service = DuelWebService(pendingDuelRegistry, userService, memberLookup)
    }

    @Test
    fun `pendingForOpponent projects registry entries to view DTOs`() {
        val createdAt = Instant.ofEpochSecond(1_700_000_000L)
        every { pendingDuelRegistry.pendingForOpponent(opponentId, guildId) } returns listOf(
            PendingDuelRegistry.PendingDuel(
                id = 1L, guildId = guildId,
                initiatorDiscordId = 100L, opponentDiscordId = opponentId,
                stake = 50L, createdAt = createdAt
            ),
            PendingDuelRegistry.PendingDuel(
                id = 2L, guildId = guildId,
                initiatorDiscordId = 101L, opponentDiscordId = opponentId,
                stake = 75L, createdAt = createdAt.plusSeconds(30)
            ),
        )
        every { memberLookup.resolveAll(guildId, setOf(100L, opponentId, 101L)) } returns mapOf(
            100L to MemberLookupHelper.MemberDisplay(name = "Alice", avatarUrl = "https://cdn/100.png"),
            opponentId to MemberLookupHelper.MemberDisplay(name = "Me", avatarUrl = null),
            // 101L intentionally omitted — should fall back to "Player …"
        )

        val rows = service.pendingForOpponent(opponentId, guildId)

        assertEquals(2, rows.size)
        // Stringified to survive 18-digit Discord-snowflake JSON round-trips
        // through JS (numeric serialization rounds past 2^53).
        assertTrue(rows.all { it.opponentDiscordId == opponentId.toString() })
        assertEquals("100", rows[0].initiatorDiscordId)
        assertEquals("Alice", rows[0].initiatorName)
        assertEquals("https://cdn/100.png", rows[0].initiatorAvatarUrl)
        assertEquals("Me", rows[0].opponentName)
        assertNull(rows[0].opponentAvatarUrl)
        assertEquals(50L, rows[0].stake)
        assertEquals(createdAt.epochSecond, rows[0].createdAtEpochSeconds)

        assertEquals("101", rows[1].initiatorDiscordId)
        // Unresolved member falls back to the helper's "Player XXXX" form.
        assertEquals("Player 101", rows[1].initiatorName)
        assertNull(rows[1].initiatorAvatarUrl)
        assertEquals(75L, rows[1].stake)
        assertEquals(createdAt.epochSecond + 30, rows[1].createdAtEpochSeconds)
    }

    @Test
    fun `pendingForInitiator enriches opponent display`() {
        val initiatorId = 100L
        every { pendingDuelRegistry.pendingForInitiator(initiatorId, guildId) } returns listOf(
            PendingDuelRegistry.PendingDuel(
                id = 1L, guildId = guildId,
                initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
                stake = 50L, createdAt = Instant.ofEpochSecond(1_700_000_500L)
            )
        )
        every { memberLookup.resolveAll(guildId, setOf(initiatorId, opponentId)) } returns mapOf(
            opponentId to MemberLookupHelper.MemberDisplay(name = "Bob", avatarUrl = "https://cdn/bob.png"),
        )

        val rows = service.pendingForInitiator(initiatorId, guildId)

        assertEquals(1, rows.size)
        assertEquals("Bob", rows[0].opponentName)
        assertEquals("https://cdn/bob.png", rows[0].opponentAvatarUrl)
        assertEquals("Player 100", rows[0].initiatorName)
        assertEquals(1_700_000_500L, rows[0].createdAtEpochSeconds)
    }

    @Test
    fun `empty registry result skips JDA lookup`() {
        every { pendingDuelRegistry.pendingForInitiator(any(), any()) } returns emptyList()

        val rows = service.pendingForInitiator(opponentId, guildId)

        assertTrue(rows.isEmpty())
        verify(exactly = 0) { memberLookup.resolveAll(any(), any()) }
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
