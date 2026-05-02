package web.service

import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.card.Card
import database.card.Rank
import database.card.Suit
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Coverage of the projection-layer enrichment we added so the web UI can
 * render real Discord names + avatars per seat. The engine-level snapshot
 * shape is exercised by `BlackjackServiceTest` over in the database
 * module — here we only assert that `displayName` / `avatarUrl` populate
 * from [MemberLookupHelper] and fall back gracefully when the helper
 * doesn't know about a seat.
 */
class BlackjackWebServiceTest {

    private lateinit var registry: BlackjackTableRegistry
    private lateinit var memberLookup: MemberLookupHelper
    private lateinit var service: BlackjackWebService

    @BeforeEach
    fun setup() {
        registry = BlackjackTableRegistry(
            idleTtl = Duration.ofMinutes(5),
            sweepInterval = Duration.ofHours(1),
        )
        memberLookup = mockk(relaxed = true)
        every { memberLookup.fallbackName(any()) } answers {
            "Player ${(it.invocation.args[0] as Long).toString().takeLast(4)}"
        }
        service = BlackjackWebService(registry, memberLookup)
    }

    private fun makeTable(seatIds: List<Long>): BlackjackTable {
        val table = registry.create(
            guildId = 42L,
            mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = seatIds.first(),
            ante = 100L,
            maxSeats = 6,
        )
        seatIds.forEach { id ->
            table.seats.add(
                BlackjackTable.Seat(
                    discordId = id,
                    hand = mutableListOf(Card(Rank.TEN, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
                    ante = 100L,
                    stake = 100L,
                )
            )
        }
        table.dealer.add(Card(Rank.NINE, Suit.SPADES))
        table.dealer.add(Card(Rank.FIVE, Suit.SPADES))
        return table
    }

    @Test
    fun `snapshot returns null for unknown table`() {
        assertNull(service.snapshot(tableId = 999L, viewerDiscordId = 1L))
    }

    @Test
    fun `snapshot enriches every seat with displayName and avatarUrl`() {
        val table = makeTable(seatIds = listOf(1L, 2L))
        every { memberLookup.resolveAll(42L, listOf(1L, 2L)) } returns mapOf(
            1L to MemberLookupHelper.MemberDisplay(name = "Alice", avatarUrl = "alice.png"),
            2L to MemberLookupHelper.MemberDisplay(name = "Bob", avatarUrl = null),
        )

        val view = service.snapshot(table.id, viewerDiscordId = 1L)
        assertNotNull(view)
        assertEquals("Alice", view!!.seats[0].displayName)
        assertEquals("alice.png", view.seats[0].avatarUrl)
        assertEquals("Bob", view.seats[1].displayName)
        // Member resolved but had no avatar URL — we still keep their real name.
        assertNull(view.seats[1].avatarUrl)
    }

    @Test
    fun `snapshot falls back to fallbackName when member is not in guild`() {
        val table = makeTable(seatIds = listOf(11119999L))
        every { memberLookup.resolveAll(42L, listOf(11119999L)) } returns emptyMap()

        val view = service.snapshot(table.id, viewerDiscordId = 11119999L)!!
        assertEquals("Player 9999", view.seats[0].displayName)
        assertNull(view.seats[0].avatarUrl)
    }

    @Test
    fun `listMultiTables stringifies hostDiscordId so it survives JS Number precision`() {
        // 18-digit Discord snowflake — past JS Number.MAX_SAFE_INTEGER (2^53).
        // Numeric serialization would round the last few digits.
        val snowflake = 553658039266443264L
        makeTable(seatIds = listOf(snowflake, 2L))
        val rows = service.listMultiTables(guildId = 42L)
        assertEquals(1, rows.size)
        assertEquals(snowflake.toString(), rows[0].hostDiscordId)
    }
}
