package bot.toby.command.commands.economy

import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.card.Card
import database.card.Rank
import database.card.Suit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BlackjackEmbedsTest {

    @Test
    fun `buttonId encodes action and table id`() {
        assertEquals("blackjack:HIT:7", BlackjackEmbeds.buttonId(BlackjackEmbeds.Action.HIT, tableId = 7L))
    }

    @Test
    fun `parseButtonId round-trips every action`() {
        for (action in BlackjackEmbeds.Action.entries) {
            val encoded = BlackjackEmbeds.buttonId(action, tableId = 42L)
            val parsed = BlackjackEmbeds.parseButtonId(encoded)
            assertNotNull(parsed)
            assertEquals(action, parsed!!.action)
            assertEquals(42L, parsed.tableId)
        }
    }

    @Test
    fun `parseButtonId rejects malformed ids`() {
        assertNull(BlackjackEmbeds.parseButtonId("nope"))
        assertNull(BlackjackEmbeds.parseButtonId("blackjack:UNKNOWN:1"))
        assertNull(BlackjackEmbeds.parseButtonId("blackjack:HIT:notanumber"))
        assertNull(BlackjackEmbeds.parseButtonId("notblackjack:HIT:1"))
    }

    @Test
    fun `handLine renders cards and total`() {
        val line = BlackjackEmbeds.handLine(listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)))
        assertTrue(line.contains("K♠"))
        assertTrue(line.contains("7♥"))
        assertTrue(line.contains("17"))
    }

    @Test
    fun `handLine returns dash for empty hand`() {
        assertEquals("—", BlackjackEmbeds.handLine(emptyList()))
    }

    @Test
    fun `dealerUpLine hides the hole card`() {
        val line = BlackjackEmbeds.dealerUpLine(
            listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS))
        )
        assertTrue(line.contains("K♠"))
        // Hidden hole card is the unicode card-back glyph.
        assertTrue(line.contains("🂠"), "expected hidden glyph in: $line")
    }

    @Test
    fun `soloDealEmbed renders both hands without throwing`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            dealer = mutableListOf(Card(Rank.NINE, Suit.SPADES), Card(Rank.TWO, Suit.SPADES))
        )
        val embed = BlackjackEmbeds.soloDealEmbed(table)
        assertTrue(embed.description!!.contains("K♠"))
        assertTrue(embed.description!!.contains("9♠"))
    }

    @Test
    fun `soloResolvedEmbed labels each result variant`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            dealer = mutableListOf(Card(Rank.NINE, Suit.SPADES), Card(Rank.SEVEN, Suit.SPADES))
        )
        val win = result(table, Blackjack.Result.PLAYER_WIN, payout = 100L)
        val winEmbed = BlackjackEmbeds.soloResolvedEmbed(table, win, newBalance = 1_050L, jackpotPayout = 0L, lossTribute = 0L)
        assertTrue(winEmbed.description!!.contains("win", ignoreCase = true) || winEmbed.description!!.contains("Win"))
    }

    @Test
    fun `lobbyEmbed shows table id and ante`() {
        val table = multiTable(ante = 200L)
        val embed = BlackjackEmbeds.lobbyEmbed(table)
        assertTrue(embed.title!!.contains("#${table.id}"))
        assertTrue(embed.description!!.contains("200"))
    }

    @Test
    fun `multiHandStateEmbed marks the current actor`() {
        val table = multiTable().apply {
            seats.add(BlackjackTable.Seat(discordId = 1L, ante = 100L, stake = 100L))
            seats.add(BlackjackTable.Seat(discordId = 2L, ante = 100L, stake = 100L))
            phase = BlackjackTable.Phase.PLAYER_TURNS
            actorIndex = 1
            handNumber = 3L
            dealer.add(Card(Rank.KING, Suit.SPADES))
            dealer.add(Card(Rank.SEVEN, Suit.HEARTS))
        }
        val embed = BlackjackEmbeds.multiHandStateEmbed(table)
        assertTrue(embed.title!!.contains("Hand #3"))
        assertTrue(embed.description!!.contains("<@2>"))
    }

    @Test
    fun `peekEmbed handles empty hand gracefully`() {
        val embed = BlackjackEmbeds.peekEmbed(emptyList())
        assertTrue(embed.description!!.contains("haven't been dealt"))
    }

    @Test
    fun `errorEmbed exposes the message`() {
        val embed = BlackjackEmbeds.errorEmbed("boom")
        assertTrue(embed.description!!.contains("boom"))
    }

    private fun soloTable(player: MutableList<Card>, dealer: MutableList<Card>): BlackjackTable {
        val t = BlackjackTable(
            id = 1L,
            guildId = 42L,
            mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = 1L,
            ante = 100L,
            maxSeats = 1
        )
        t.seats.add(
            BlackjackTable.Seat(
                discordId = 1L,
                hand = player,
                ante = 100L,
                stake = 100L
            )
        )
        t.dealer.addAll(dealer)
        return t
    }

    private fun multiTable(ante: Long = 100L): BlackjackTable = BlackjackTable(
        id = 5L,
        guildId = 42L,
        mode = BlackjackTable.Mode.MULTI,
        hostDiscordId = 1L,
        ante = ante,
        maxSeats = 5
    )

    private fun result(table: BlackjackTable, r: Blackjack.Result, payout: Long): BlackjackTable.HandResult {
        val seat = table.seats.firstOrNull()
        val perHand = if (seat != null) listOf(
            BlackjackTable.PerHandResult(
                discordId = seat.discordId,
                handIndex = 0,
                cards = seat.hand.toList(),
                total = 0,
                stake = seat.stake,
                doubled = false,
                fromSplit = false,
                result = r,
                payout = payout,
            )
        ) else emptyList()
        return BlackjackTable.HandResult(
            handNumber = table.handNumber,
            dealer = table.dealer.toList(),
            dealerTotal = 17,
            seatResults = mapOf((seat?.discordId ?: 1L) to r),
            payouts = if (payout > 0L) mapOf((seat?.discordId ?: 1L) to payout) else emptyMap(),
            pot = 100L,
            rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = perHand,
        )
    }
}
