package web.service

import common.card.Card
import common.card.Rank
import common.card.Suit
import common.casino.casinoholdem.CasinoHoldem
import common.casino.casinoholdem.CasinoHoldemTable
import database.poker.CasinoHoldemTableRegistry
import common.casino.poker.HandEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

class CasinoHoldemWebServiceTest {

    private lateinit var registry: CasinoHoldemTableRegistry
    private lateinit var service: CasinoHoldemWebService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        registry = CasinoHoldemTableRegistry(
            idleTtl = Duration.ofMinutes(10),
            sweepInterval = Duration.ofHours(1),
            scheduler = Executors.newScheduledThreadPool(1),
        )
        service = CasinoHoldemWebService(registry)
    }

    private fun seedTable(): CasinoHoldemTable {
        val t = registry.create(guildId, discordId, stake = 100L)
        t.playerHole.add(Card(Rank.ACE, Suit.SPADES))
        t.playerHole.add(Card(Rank.KING, Suit.SPADES))
        t.dealerHole.add(Card(Rank.TWO, Suit.HEARTS))
        t.dealerHole.add(Card(Rank.THREE, Suit.DIAMONDS))
        t.board.add(Card(Rank.QUEEN, Suit.SPADES))
        t.board.add(Card(Rank.JACK, Suit.SPADES))
        t.board.add(Card(Rank.TEN, Suit.SPADES))
        t.pendingTurn = Card(Rank.NINE, Suit.CLUBS)
        t.pendingRiver = Card(Rank.EIGHT, Suit.DIAMONDS)
        return t
    }

    @Test
    fun `snapshot masks dealer hole during AWAIT_DECISION`() {
        seedTable()
        val view = service.snapshot(tableId = 1L, viewerDiscordId = discordId)

        assertNotNull(view)
        view!!
        assertEquals("AWAIT_DECISION", view.phase)
        // Player + board fully visible.
        assertEquals(listOf("A♠", "K♠"), view.playerHole)
        assertEquals(listOf("Q♠", "J♠", "10♠"), view.board)
        // Dealer hole masked: same length, all '??'.
        assertEquals(2, view.dealerHole.size)
        assertTrue(view.dealerHole.all { it == "??" })
        assertEquals(100L, view.stake)
        assertEquals(200L, view.callStake)
    }

    @Test
    fun `snapshot reveals dealer hole on RESOLVED showdown (non-fold)`() {
        val t = seedTable()
        t.phase = CasinoHoldemTable.Phase.RESOLVED
        val rank = HandEvaluator.HandRank(HandEvaluator.Category.PAIR, listOf(5, 14, 12, 11))
        t.lastResult = CasinoHoldemTable.HandResult(
            playerHole = t.playerHole.toList(),
            dealerHole = t.dealerHole.toList(),
            board = t.board.toList(),
            resolution = CasinoHoldem.Resolution(
                playerRank = rank,
                dealerRank = rank,
                dealerQualified = true,
                anteResult = CasinoHoldem.AnteResult.WIN,
                callResult = CasinoHoldem.CallResult.WIN_OTHER,
            ),
            folded = false,
            anteStake = 100L,
            callStake = 200L,
            antePayout = 200L,
            callPayout = 400L,
            totalPayout = 600L,
            resolvedAt = Instant.now(),
        )

        val view = service.snapshot(tableId = 1L, viewerDiscordId = discordId)

        assertNotNull(view)
        view!!
        assertEquals("RESOLVED", view.phase)
        // Both dealer cards revealed.
        assertEquals(listOf("2♥", "3♦"), view.dealerHole)
        // lastResult is populated.
        val r = view.lastResult
        assertNotNull(r)
        r!!
        assertFalse(r.folded)
        assertEquals(true, r.dealerQualified)
        assertEquals("WIN", r.anteResult)
        assertEquals("WIN_OTHER", r.callResult)
        assertEquals(600L, r.totalPayout)
    }

    @Test
    fun `snapshot keeps dealer hole hidden on RESOLVED fold`() {
        val t = seedTable()
        t.phase = CasinoHoldemTable.Phase.RESOLVED
        t.lastResult = CasinoHoldemTable.HandResult(
            playerHole = t.playerHole.toList(),
            dealerHole = t.dealerHole.toList(),
            board = t.board.toList(),
            resolution = null,
            folded = true,
            anteStake = 100L,
            callStake = 0L,
            antePayout = 0L,
            callPayout = 0L,
            totalPayout = 0L,
            resolvedAt = Instant.now(),
        )

        val view = service.snapshot(tableId = 1L, viewerDiscordId = discordId)

        assertNotNull(view)
        view!!
        assertEquals("RESOLVED", view.phase)
        assertTrue(view.dealerHole.all { it == "??" })
        assertTrue(view.lastResult!!.folded)
        assertTrue(view.lastResult!!.dealerHole.all { it == "??" })
        assertNull(view.lastResult!!.anteResult)
    }

    @Test
    fun `snapshot returns null when viewer is not the table owner`() {
        seedTable()
        val view = service.snapshot(tableId = 1L, viewerDiscordId = 999L)
        assertNull(view)
    }

    @Test
    fun `findActiveTable prefers AWAIT_DECISION over a stale RESOLVED leftover`() {
        // First create a RESOLVED leftover, then a fresh AWAIT_DECISION.
        val stale = registry.create(guildId, discordId, stake = 100L)
        stale.phase = CasinoHoldemTable.Phase.RESOLVED
        registry.create(guildId, discordId, stake = 200L)

        val active = service.findActiveTable(guildId, discordId)
        assertEquals(2L, active)
    }

    @Test
    fun `findActiveTable returns null when player has no tables in this guild`() {
        assertNull(service.findActiveTable(guildId, discordId))
    }
}
