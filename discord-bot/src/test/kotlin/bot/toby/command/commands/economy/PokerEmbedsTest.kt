package bot.toby.command.commands.economy

import database.card.Card
import database.poker.PokerTable
import database.card.Rank
import database.card.Suit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerEmbedsTest {

    @Test
    fun `buttonId encodes action and table id`() {
        val id = PokerEmbeds.buttonId(PokerEmbeds.Action.FOLD, tableId = 7L)
        assertEquals("poker:FOLD:7", id)
    }

    @Test
    fun `parseButtonId round-trips every action`() {
        for (action in PokerEmbeds.Action.entries) {
            val encoded = PokerEmbeds.buttonId(action, tableId = 42L)
            val parsed = PokerEmbeds.parseButtonId(encoded)
            assertNotNull(parsed)
            assertEquals(action, parsed!!.action)
            assertEquals(42L, parsed.tableId)
        }
    }

    @Test
    fun `parseButtonId rejects malformed ids`() {
        assertNull(PokerEmbeds.parseButtonId("nope"))
        assertNull(PokerEmbeds.parseButtonId("poker:UNKNOWN_ACTION:1"))
        assertNull(PokerEmbeds.parseButtonId("poker:FOLD:notanumber"))
        assertNull(PokerEmbeds.parseButtonId("notpoker:FOLD:1"))
    }

    @Test
    fun `lobbyEmbed renders and includes table id`() {
        val table = stubTable()
        val embed = PokerEmbeds.lobbyEmbed(table)
        assertTrue(embed.title!!.contains("#${table.id}"))
    }

    @Test
    fun `peekEmbed handles empty hand gracefully`() {
        val embed = PokerEmbeds.peekEmbed(emptyList())
        assertTrue(embed.description!!.contains("haven't been dealt"))
    }

    @Test
    fun `peekEmbed lists the cards when present`() {
        val embed = PokerEmbeds.peekEmbed(listOf(Card(Rank.ACE, Suit.SPADES)))
        assertTrue(embed.description!!.contains("A♠"))
    }

    @Test
    fun `errorEmbed and infoEmbed produce distinct titles or colors`() {
        val err = PokerEmbeds.errorEmbed("boom")
        val info = PokerEmbeds.infoEmbed("ok")
        assertTrue(err.description!!.contains("boom"))
        assertTrue(info.description!!.contains("ok"))
    }

    @Test
    fun `handStateEmbed and resultEmbed render without throwing`() {
        val table = stubTable().apply {
            phase = PokerTable.Phase.FLOP
            community.add(Card(Rank.ACE, Suit.SPADES))
            seats.add(PokerTable.Seat(discordId = 1L, chips = 500L))
            seats.add(PokerTable.Seat(discordId = 2L, chips = 500L))
            actorIndex = 0
            currentBet = 10L
            pot = 30L
            handNumber = 3L
        }
        val state = PokerEmbeds.handStateEmbed(table)
        assertTrue(state.title!!.contains("Hand #3"))

        val result = PokerTable.HandResult(
            handNumber = 3L,
            winners = listOf(1L),
            payoutByDiscordId = mapOf(1L to 28L),
            pot = 30L,
            rake = 2L,
            board = listOf(Card(Rank.ACE, Suit.SPADES)),
            revealedHoleCards = mapOf(1L to listOf(Card(Rank.KING, Suit.HEARTS))),
            resolvedAt = java.time.Instant.now()
        )
        val rendered = PokerEmbeds.resultEmbed(table, result)
        assertTrue(rendered.title!!.contains("Hand #3 settled"))
    }

    private fun stubTable() = PokerTable(
        id = 11L,
        guildId = 42L,
        hostDiscordId = 1L,
        minBuyIn = 100L,
        maxBuyIn = 5000L,
        smallBlind = 5L,
        bigBlind = 10L,
        smallBet = 10L,
        bigBet = 20L,
        maxRaisesPerStreet = 4,
        maxSeats = 6,
    )
}
