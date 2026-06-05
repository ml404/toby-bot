package bot.toby.command.commands.game.casino.casinoholdem

import common.card.Card
import common.card.Rank
import common.card.Suit
import common.casino.casinoholdem.CasinoHoldem
import common.casino.casinoholdem.CasinoHoldemTable
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure-logic coverage for the `/casinoholdem` embed/component plumbing.
 * Mirrors [bot.toby.command.commands.game.casino.blackjack.BlackjackEmbeds]'s
 * test — no JDA gateway, just the button-id codec and embed builders.
 */
class CasinoHoldemEmbedsTest {

    private fun card(rank: Rank, suit: Suit) = Card(rank, suit)

    // --- button id codec -------------------------------------------------

    @Test
    fun `buttonId encodes action and table id`() {
        assertEquals("casinoholdem:CALL:7", CasinoHoldemEmbeds.buttonId(CasinoHoldemEmbeds.Action.CALL, 7L))
        assertEquals("casinoholdem:FOLD:9", CasinoHoldemEmbeds.buttonId(CasinoHoldemEmbeds.Action.FOLD, 9L))
    }

    @Test
    fun `parseButtonId round-trips every action`() {
        for (action in CasinoHoldemEmbeds.Action.entries) {
            val encoded = CasinoHoldemEmbeds.buttonId(action, tableId = 42L)
            val parsed = CasinoHoldemEmbeds.parseButtonId(encoded)
            assertNotNull(parsed)
            assertEquals(action, parsed!!.action)
            assertEquals(42L, parsed.tableId)
        }
    }

    @Test
    fun `parseButtonId is case-insensitive on name and action`() {
        val parsed = CasinoHoldemEmbeds.parseButtonId("CASINOHOLDEM:call:5")
        assertNotNull(parsed)
        assertEquals(CasinoHoldemEmbeds.Action.CALL, parsed!!.action)
        assertEquals(5L, parsed.tableId)
    }

    @Test
    fun `parseButtonId rejects malformed ids`() {
        assertNull(CasinoHoldemEmbeds.parseButtonId("nope"))
        assertNull(CasinoHoldemEmbeds.parseButtonId("casinoholdem:UNKNOWN:1"))
        assertNull(CasinoHoldemEmbeds.parseButtonId("casinoholdem:CALL:notanumber"))
        assertNull(CasinoHoldemEmbeds.parseButtonId("notholdem:CALL:1"))
        assertNull(CasinoHoldemEmbeds.parseButtonId("casinoholdem:CALL:1:extra"))
        assertNull(CasinoHoldemEmbeds.parseButtonId("casinoholdem:CALL"))
    }

    // --- deal embed ------------------------------------------------------

    @Test
    fun `dealEmbed renders board, player hand, hidden dealer and call cost`() {
        val table = CasinoHoldemTable(
            id = 1L,
            guildId = 100L,
            playerDiscordId = 200L,
            stake = 50L,
            playerHole = mutableListOf(card(Rank.ACE, Suit.SPADES), card(Rank.KING, Suit.SPADES)),
            board = mutableListOf(
                card(Rank.TWO, Suit.HEARTS),
                card(Rank.SEVEN, Suit.DIAMONDS),
                card(Rank.NINE, Suit.CLUBS),
            ),
        )

        val embed = CasinoHoldemEmbeds.dealEmbed(table)
        val desc = embed.description
        assertNotNull(desc)
        assertTrue(desc!!.contains("A♠"), desc)
        assertTrue(desc.contains("2♥"), desc)
        // Dealer hole stays hidden behind the card-back glyph.
        assertTrue(desc.contains("🂠"), desc)
        val callCost = 50L * CasinoHoldem.CALL_MULTIPLE
        assertTrue(desc.contains(callCost.toString()), desc)
        assertEquals("🃏 Casino Hold'em • 50 credits ante", embed.title)
    }

    @Test
    fun `dealEmbed renders dash for an empty board`() {
        val table = CasinoHoldemTable(
            id = 1L,
            guildId = 100L,
            playerDiscordId = 200L,
            stake = 10L,
            playerHole = mutableListOf(card(Rank.ACE, Suit.SPADES), card(Rank.KING, Suit.SPADES)),
        )
        val desc = CasinoHoldemEmbeds.dealEmbed(table).description
        assertNotNull(desc)
        assertTrue(desc!!.contains("—"), desc)
    }

    // --- resolved embed --------------------------------------------------

    private fun emptyTable() = CasinoHoldemTable(
        id = 1L,
        guildId = 100L,
        playerDiscordId = 200L,
        stake = 50L,
    )

    @Test
    fun `resolvedEmbed folded path forfeits the ante`() {
        val result = CasinoHoldemTable.HandResult(
            playerHole = listOf(card(Rank.ACE, Suit.SPADES), card(Rank.KING, Suit.SPADES)),
            dealerHole = listOf(card(Rank.TWO, Suit.HEARTS), card(Rank.THREE, Suit.HEARTS)),
            board = listOf(card(Rank.FOUR, Suit.CLUBS), card(Rank.FIVE, Suit.CLUBS), card(Rank.SIX, Suit.CLUBS)),
            resolution = null,
            folded = true,
            anteStake = 50L,
            callStake = 0L,
            antePayout = 0L,
            callPayout = 0L,
            totalPayout = 0L,
            resolvedAt = Instant.now(),
        )

        val embed = CasinoHoldemEmbeds.resolvedEmbed(emptyTable(), result, newBalance = 950L, jackpotPayout = 0L, lossTribute = 0L)
        val desc = embed.description
        assertNotNull(desc)
        assertTrue(desc!!.contains("folded"), desc)
        assertTrue(desc.contains("50"), desc)
        // Dealer hole stays hidden on a fold.
        assertTrue(desc.contains("🂠"), desc)
        assertEquals("🃏 Casino Hold'em", embed.title)
    }

    @Test
    fun `resolvedEmbed showdown with null resolution falls back to a generic line`() {
        val result = showdownResult(resolution = null, net = 0L)
        val desc = CasinoHoldemEmbeds.resolvedEmbed(emptyTable(), result, 1000L, 0L, 0L).description
        assertNotNull(desc)
        assertTrue(desc!!.contains("Hand resolved."), desc)
    }

    @Test
    fun `resolvedEmbed surfaces jackpot win and loss tribute lines`() {
        val result = showdownResult(
            resolution = resolution(CasinoHoldem.AnteResult.WIN, CasinoHoldem.CallResult.WIN_FLUSH, qualified = true),
            net = 120L,
        )
        val desc = CasinoHoldemEmbeds.resolvedEmbed(emptyTable(), result, 2000L, jackpotPayout = 500L, lossTribute = 25L).description
        assertNotNull(desc)
        assertTrue(desc!!.contains("Jackpot hit"), desc)
        assertTrue(desc.contains("500"), desc)
        assertTrue(desc.contains("jackpot pool"), desc)
        assertTrue(desc.contains("25"), desc)
    }

    @Test
    fun `resolvedEmbed notes when the dealer fails to qualify`() {
        val result = showdownResult(
            resolution = resolution(CasinoHoldem.AnteResult.WIN, CasinoHoldem.CallResult.PUSH, qualified = false),
            net = 0L,
        )
        val desc = CasinoHoldemEmbeds.resolvedEmbed(emptyTable(), result, 1000L, 0L, 0L).description
        assertNotNull(desc)
        assertTrue(desc!!.contains("didn't qualify"), desc)
    }

    @Test
    fun `resolvedEmbed renders every ante and call leg label without throwing`() {
        for (ante in CasinoHoldem.AnteResult.entries) {
            for (call in CasinoHoldem.CallResult.entries) {
                for (net in listOf(-50L, 0L, 250L)) {
                    val result = showdownResult(resolution(ante, call, qualified = true), net)
                    val embed = CasinoHoldemEmbeds.resolvedEmbed(emptyTable(), result, 1000L, 0L, 0L)
                    assertNotNull(embed.description, "ante=$ante call=$call net=$net")
                }
            }
        }
    }

    @Test
    fun `resolvedEmbed spells out the headline jackpot multipliers`() {
        val royal = showdownResult(
            resolution(CasinoHoldem.AnteResult.WIN, CasinoHoldem.CallResult.WIN_ROYAL_FLUSH, qualified = true),
            net = 9000L,
        )
        val desc = CasinoHoldemEmbeds.resolvedEmbed(emptyTable(), royal, 1000L, 0L, 0L).description
        assertNotNull(desc)
        assertTrue(desc!!.contains("100:1"), desc)
        assertTrue(desc.contains("Net **+"), desc)
    }

    private fun resolution(
        ante: CasinoHoldem.AnteResult,
        call: CasinoHoldem.CallResult,
        qualified: Boolean,
    ): CasinoHoldem.Resolution = mockk {
        every { dealerQualified } returns qualified
        every { anteResult } returns ante
        every { callResult } returns call
    }

    /**
     * Build a showdown [CasinoHoldemTable.HandResult] whose `net` getter
     * yields [net]. `net = totalPayout - (anteStake + callStake)`, so we
     * fix the at-risk legs and back out the payout.
     */
    private fun showdownResult(resolution: CasinoHoldem.Resolution?, net: Long): CasinoHoldemTable.HandResult {
        val anteStake = 50L
        val callStake = 100L
        val totalPayout = anteStake + callStake + net
        return CasinoHoldemTable.HandResult(
            playerHole = listOf(card(Rank.ACE, Suit.SPADES), card(Rank.KING, Suit.SPADES)),
            dealerHole = listOf(card(Rank.QUEEN, Suit.HEARTS), card(Rank.JACK, Suit.HEARTS)),
            board = listOf(
                card(Rank.TEN, Suit.CLUBS),
                card(Rank.NINE, Suit.CLUBS),
                card(Rank.EIGHT, Suit.CLUBS),
                card(Rank.TWO, Suit.DIAMONDS),
                card(Rank.THREE, Suit.SPADES),
            ),
            resolution = resolution,
            folded = false,
            anteStake = anteStake,
            callStake = callStake,
            antePayout = anteStake,
            callPayout = callStake,
            totalPayout = totalPayout,
            resolvedAt = Instant.now(),
        )
    }
}
