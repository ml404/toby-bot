package web.service

import database.duel.PendingDuelRegistry
import database.duel.RecentDuelResolutions
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

class PvpWebServiceTest {

    private val opponentId = 200L
    private val guildId = 42L

    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var userService: UserService
    private lateinit var memberLookup: MemberLookupHelper
    private lateinit var recentDuelResolutions: RecentDuelResolutions
    private lateinit var service: PvpWebService

    @BeforeEach
    fun setup() {
        pendingDuelRegistry = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        memberLookup = mockk {
            every { resolveAll(any(), any()) } returns emptyMap()
            every { fallbackName(any()) } answers { "Player ${firstArg<Long>().toString().takeLast(4)}" }
        }
        recentDuelResolutions = mockk {
            every { consumeForInitiator(any(), any()) } returns emptyList()
        }
        service = PvpWebService(pendingDuelRegistry, userService, memberLookup, recentDuelResolutions)
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

        val rows = service.duelPendingForOpponent(opponentId, guildId)

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

        val rows = service.duelPendingForInitiator(initiatorId, guildId)

        assertEquals(1, rows.size)
        assertEquals("Bob", rows[0].opponentName)
        assertEquals("https://cdn/bob.png", rows[0].opponentAvatarUrl)
        assertEquals("Player 100", rows[0].initiatorName)
        assertEquals(1_700_000_500L, rows[0].createdAtEpochSeconds)
    }

    @Test
    fun `empty registry result skips JDA lookup`() {
        every { pendingDuelRegistry.pendingForInitiator(any(), any()) } returns emptyList()

        val rows = service.duelPendingForInitiator(opponentId, guildId)

        assertTrue(rows.isEmpty())
        verify(exactly = 0) { memberLookup.resolveAll(any(), any()) }
    }

    @Test
    fun `outgoingPayload bundles pending offers and recent resolutions`() {
        val initiatorId = 100L
        val createdAt = Instant.ofEpochSecond(1_700_000_000L)
        every { pendingDuelRegistry.pendingForInitiator(initiatorId, guildId) } returns listOf(
            PendingDuelRegistry.PendingDuel(
                id = 7L, guildId = guildId,
                initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
                stake = 50L, createdAt = createdAt
            )
        )
        every { recentDuelResolutions.consumeForInitiator(initiatorId, guildId) } returns listOf(
            RecentDuelResolutions.Resolution(
                guildId = guildId,
                initiatorDiscordId = initiatorId,
                opponentDiscordId = opponentId,
                winnerDiscordId = opponentId,
                loserDiscordId = initiatorId,
                stake = 25L,
                pot = 50L,
                lossTribute = 5L,
                resolvedAt = createdAt,
            )
        )
        // Two distinct lookups happen — one for pending, one for resolutions.
        every { memberLookup.resolveAll(guildId, setOf(initiatorId, opponentId)) } returns mapOf(
            initiatorId to MemberLookupHelper.MemberDisplay(name = "Alice", avatarUrl = "https://cdn/a.png"),
            opponentId to MemberLookupHelper.MemberDisplay(name = "Bob", avatarUrl = "https://cdn/b.png"),
        )

        val payload = service.duelOutgoingPayload(initiatorId, guildId)

        // Pending side
        assertEquals(1, payload.pending.size)
        assertEquals("Alice", payload.pending[0].initiatorName)
        assertEquals("Bob", payload.pending[0].opponentName)
        // Resolutions side
        assertEquals(1, payload.resolutions.size)
        val r = payload.resolutions[0]
        assertEquals(initiatorId.toString(), r.initiatorDiscordId)
        assertEquals("Alice", r.initiatorName)
        assertEquals("https://cdn/a.png", r.initiatorAvatarUrl)
        assertEquals(opponentId.toString(), r.opponentDiscordId)
        assertEquals("Bob", r.opponentName)
        assertEquals(opponentId.toString(), r.winnerDiscordId)
        assertEquals(50L, r.pot)
        assertEquals(5L, r.lossTribute)
    }

    @Test
    fun `outgoingPayload falls back to Player XXXX when a participant left the guild`() {
        val initiatorId = 100L
        every { pendingDuelRegistry.pendingForInitiator(initiatorId, guildId) } returns emptyList()
        every { recentDuelResolutions.consumeForInitiator(initiatorId, guildId) } returns listOf(
            RecentDuelResolutions.Resolution(
                guildId = guildId,
                initiatorDiscordId = initiatorId,
                opponentDiscordId = opponentId,
                winnerDiscordId = initiatorId,
                loserDiscordId = opponentId,
                stake = 25L,
                pot = 50L,
                lossTribute = 5L,
                resolvedAt = Instant.ofEpochSecond(1_700_000_000L),
            )
        )
        // JDA returns nothing — both members fall back.
        every { memberLookup.resolveAll(guildId, setOf(initiatorId, opponentId)) } returns emptyMap()

        val payload = service.duelOutgoingPayload(initiatorId, guildId)

        assertEquals(1, payload.resolutions.size)
        assertEquals("Player 100", payload.resolutions[0].initiatorName)
        assertEquals("Player 200", payload.resolutions[0].opponentName)
        assertNull(payload.resolutions[0].initiatorAvatarUrl)
    }

    @Test
    fun `outgoingPayload short-circuits the resolution lookup when none are pending`() {
        val initiatorId = 100L
        every { pendingDuelRegistry.pendingForInitiator(initiatorId, guildId) } returns emptyList()
        every { recentDuelResolutions.consumeForInitiator(initiatorId, guildId) } returns emptyList()

        val payload = service.duelOutgoingPayload(initiatorId, guildId)

        assertTrue(payload.pending.isEmpty())
        assertTrue(payload.resolutions.isEmpty())
        // pendingForInitiator's project() guards on its own; resolutions
        // path's resolveAll call should NOT fire when nothing is to resolve.
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
