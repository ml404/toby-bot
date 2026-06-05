package bot.toby.command.commands.game.casino.baccarat

import common.card.Card
import common.card.Rank
import common.card.Suit
import common.casino.baccarat.Baccarat
import database.service.casino.baccarat.BaccaratService.PlayOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class BaccaratEmbedsTest {

    // ── button-id encoding ───────────────────────────────────────────────────

    @Test
    fun `sideButtonId encodes prefix side stake and userId`() {
        val id = BaccaratEmbeds.sideButtonId(Baccarat.Side.PLAYER, stake = 100L, userId = 7L)
        assertEquals("baccarat:PLAYER:100:7", id)
    }

    @Test
    fun `parseButtonId round-trips every Side`() {
        for (side in Baccarat.Side.entries) {
            val encoded = BaccaratEmbeds.sideButtonId(side, stake = 50L, userId = 42L)
            val parsed = BaccaratEmbeds.parseButtonId(encoded)
            assertNotNull(parsed, "Expected non-null for side $side")
            assertEquals(side, parsed!!.side)
            assertEquals(50L, parsed.stake)
            assertEquals(42L, parsed.userId)
        }
    }

    @Test
    fun `parseButtonId rejects too few parts`() {
        assertNull(BaccaratEmbeds.parseButtonId("baccarat:PLAYER:100"))
    }

    @Test
    fun `parseButtonId rejects wrong prefix`() {
        assertNull(BaccaratEmbeds.parseButtonId("notbaccarat:PLAYER:100:7"))
    }

    @Test
    fun `parseButtonId rejects unknown side`() {
        assertNull(BaccaratEmbeds.parseButtonId("baccarat:UNKNOWN:100:7"))
    }

    @Test
    fun `parseButtonId rejects non-numeric stake`() {
        assertNull(BaccaratEmbeds.parseButtonId("baccarat:PLAYER:notanumber:7"))
    }

    @Test
    fun `parseButtonId rejects non-numeric userId`() {
        assertNull(BaccaratEmbeds.parseButtonId("baccarat:PLAYER:100:notanumber"))
    }

    @Test
    fun `parseButtonId accepts case-insensitive prefix`() {
        val parsed = BaccaratEmbeds.parseButtonId("BACCARAT:BANKER:200:9")
        assertNotNull(parsed)
        assertEquals(Baccarat.Side.BANKER, parsed!!.side)
        assertEquals(200L, parsed.stake)
        assertEquals(9L, parsed.userId)
    }

    // ── handLine ─────────────────────────────────────────────────────────────

    @Test
    fun `handLine returns dash for empty list`() {
        assertEquals("—", BaccaratEmbeds.handLine(emptyList(), total = 0, isNatural = false))
    }

    @Test
    fun `handLine renders two cards with total`() {
        val cards = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS))
        val line = BaccaratEmbeds.handLine(cards, total = 7, isNatural = false)
        assertTrue(line.contains("K♠"), "expected K♠ in: $line")
        assertTrue(line.contains("7♥"), "expected 7♥ in: $line")
        assertTrue(line.contains("7"), "expected total 7 in: $line")
    }

    @Test
    fun `handLine appends Natural badge when isNatural`() {
        val cards = listOf(Card(Rank.EIGHT, Suit.CLUBS), Card(Rank.ACE, Suit.DIAMONDS))
        val line = BaccaratEmbeds.handLine(cards, total = 9, isNatural = true)
        assertTrue(line.contains("Natural"), "expected Natural badge in: $line")
    }

    @Test
    fun `handLine does not append Natural badge when not natural`() {
        val cards = listOf(Card(Rank.FIVE, Suit.CLUBS), Card(Rank.THREE, Suit.DIAMONDS))
        val line = BaccaratEmbeds.handLine(cards, total = 8, isNatural = false)
        assertTrue(!line.contains("Natural"), "did not expect Natural badge in: $line")
    }

    @Test
    fun `handLine renders three cards with arrow separator`() {
        val cards = listOf(
            Card(Rank.THREE, Suit.SPADES),
            Card(Rank.TWO, Suit.HEARTS),
            Card(Rank.FIVE, Suit.CLUBS)
        )
        val line = BaccaratEmbeds.handLine(cards, total = 0, isNatural = false)
        assertTrue(line.contains("→"), "expected arrow separator in: $line")
        assertTrue(line.contains("3♠"), "expected 3♠ in: $line")
        assertTrue(line.contains("5♣"), "expected 5♣ in: $line")
    }

    // ── promptEmbed ───────────────────────────────────────────────────────────

    @Test
    fun `promptEmbed title includes stake`() {
        val embed = BaccaratEmbeds.promptEmbed(stake = 250L)
        assertTrue(embed.title!!.contains("250"), "expected stake 250 in title: ${embed.title}")
    }

    @Test
    fun `promptEmbed description mentions all three sides`() {
        val embed = BaccaratEmbeds.promptEmbed(stake = 100L)
        val desc = embed.description!!
        assertTrue(desc.contains("Player"), "expected Player in description")
        assertTrue(desc.contains("Banker"), "expected Banker in description")
        assertTrue(desc.contains("Tie"), "expected Tie in description")
    }

    // ── outcomeEmbed: Win ─────────────────────────────────────────────────────

    @Test
    fun `outcomeEmbed Win player-side shows player wins verdict`() {
        val outcome = winOutcome(side = Baccarat.Side.PLAYER, jackpotPayout = 0L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertNotNull(embed.description)
        assertTrue(embed.description!!.contains("Player wins"), "expected 'Player wins' in: ${embed.description}")
        assertTrue(embed.description!!.contains("Player:"), "expected Player hand label")
        assertTrue(embed.description!!.contains("Banker:"), "expected Banker hand label")
    }

    @Test
    fun `outcomeEmbed Win banker-side shows banker wins verdict with commission note`() {
        val outcome = winOutcome(side = Baccarat.Side.BANKER, jackpotPayout = 0L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(
            embed.description!!.contains("commission", ignoreCase = true),
            "expected commission note in: ${embed.description}"
        )
    }

    @Test
    fun `outcomeEmbed Win tie-side shows Tie exclamation`() {
        val outcome = winOutcome(side = Baccarat.Side.TIE, jackpotPayout = 0L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(embed.description!!.contains("Tie!"), "expected 'Tie!' in: ${embed.description}")
    }

    @Test
    fun `outcomeEmbed Win with jackpot shows jackpot line`() {
        val outcome = winOutcome(side = Baccarat.Side.PLAYER, jackpotPayout = 5000L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(embed.description!!.contains("Jackpot"), "expected Jackpot in: ${embed.description}")
        assertTrue(embed.description!!.contains("5000"), "expected jackpot amount in: ${embed.description}")
    }

    @Test
    fun `outcomeEmbed Win without jackpot does not show jackpot line`() {
        val outcome = winOutcome(side = Baccarat.Side.PLAYER, jackpotPayout = 0L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(!embed.description!!.contains("Jackpot"), "did not expect Jackpot in: ${embed.description}")
    }

    @Test
    fun `outcomeEmbed Win includes new balance field`() {
        val outcome = winOutcome(side = Baccarat.Side.PLAYER, jackpotPayout = 0L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(embed.fields.any { it.name == "New balance" }, "expected 'New balance' field")
    }

    // ── outcomeEmbed: Push ────────────────────────────────────────────────────

    @Test
    fun `outcomeEmbed Push shows tie-game refund message`() {
        val outcome = pushOutcome(side = Baccarat.Side.PLAYER)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(embed.description!!.contains("Tie game"), "expected 'Tie game' in: ${embed.description}")
        assertTrue(embed.description!!.contains("refunded"), "expected 'refunded' in: ${embed.description}")
    }

    @Test
    fun `outcomeEmbed Push includes stake amount in description`() {
        val outcome = pushOutcome(side = Baccarat.Side.BANKER)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(embed.description!!.contains("75"), "expected stake 75 in: ${embed.description}")
    }

    @Test
    fun `outcomeEmbed Push includes new balance field`() {
        val outcome = pushOutcome(side = Baccarat.Side.PLAYER)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(embed.fields.any { it.name == "New balance" }, "expected 'New balance' field")
    }

    // ── outcomeEmbed: Lose ────────────────────────────────────────────────────

    @Test
    fun `outcomeEmbed Lose shows lost credits message`() {
        val outcome = loseOutcome(side = Baccarat.Side.PLAYER, tribute = 0L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(embed.description!!.contains("Lost"), "expected 'Lost' in: ${embed.description}")
        assertTrue(embed.description!!.contains("100"), "expected stake 100 in: ${embed.description}")
    }

    @Test
    fun `outcomeEmbed Lose with tribute shows jackpot pool line`() {
        val outcome = loseOutcome(side = Baccarat.Side.PLAYER, tribute = 10L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(
            embed.description!!.contains("jackpot pool"),
            "expected jackpot pool in: ${embed.description}"
        )
        assertTrue(embed.description!!.contains("10"), "expected tribute amount in: ${embed.description}")
    }

    @Test
    fun `outcomeEmbed Lose without tribute does not show jackpot pool line`() {
        val outcome = loseOutcome(side = Baccarat.Side.PLAYER, tribute = 0L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(
            !embed.description!!.contains("jackpot pool"),
            "did not expect jackpot pool in: ${embed.description}"
        )
    }

    @Test
    fun `outcomeEmbed Lose includes new balance field`() {
        val outcome = loseOutcome(side = Baccarat.Side.PLAYER, tribute = 0L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertTrue(embed.fields.any { it.name == "New balance" }, "expected 'New balance' field")
    }

    // ── outcomeEmbed: failure variants ────────────────────────────────────────

    @Test
    fun `outcomeEmbed InsufficientCredits contains stake and have`() {
        val outcome = PlayOutcome.InsufficientCredits(stake = 200L, have = 50L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        val desc = embed.description!!
        assertTrue(desc.contains("200"), "expected stake 200 in: $desc")
        assertTrue(desc.contains("50"), "expected have 50 in: $desc")
    }

    @Test
    fun `outcomeEmbed InsufficientCoinsForTopUp contains needed and have`() {
        val outcome = PlayOutcome.InsufficientCoinsForTopUp(needed = 300L, have = 10L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        val desc = embed.description!!
        assertTrue(desc.contains("300"), "expected needed 300 in: $desc")
        assertTrue(desc.contains("10"), "expected have 10 in: $desc")
    }

    @Test
    fun `outcomeEmbed InvalidStake contains min and max`() {
        val outcome = PlayOutcome.InvalidStake(min = 10L, max = 500L)
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        val desc = embed.description!!
        assertTrue(desc.contains("10"), "expected min 10 in: $desc")
        assertTrue(desc.contains("500"), "expected max 500 in: $desc")
    }

    @Test
    fun `outcomeEmbed UnknownUser contains user hint`() {
        val outcome = PlayOutcome.UnknownUser
        val embed = BaccaratEmbeds.outcomeEmbed(outcome)
        assertNotNull(embed.description)
        assertTrue(embed.description!!.isNotBlank())
    }

    // ── errorEmbed ────────────────────────────────────────────────────────────

    @Test
    fun `errorEmbed exposes the message in description`() {
        val embed = BaccaratEmbeds.errorEmbed("something went wrong")
        assertTrue(embed.description!!.contains("something went wrong"))
    }

    @Test
    fun `errorEmbed includes game title`() {
        val embed = BaccaratEmbeds.errorEmbed("test error")
        assertTrue(embed.title!!.contains("Baccarat"), "expected Baccarat in title: ${embed.title}")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private val playerCards = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.THREE, Suit.HEARTS))
    private val bankerCards = listOf(Card(Rank.SEVEN, Suit.CLUBS), Card(Rank.FIVE, Suit.DIAMONDS))

    private fun winOutcome(side: Baccarat.Side, jackpotPayout: Long): PlayOutcome.Win =
        PlayOutcome.Win(
            stake = 100L,
            payout = 200L,
            net = 100L,
            side = side,
            winner = side,
            playerCards = playerCards,
            bankerCards = bankerCards,
            playerTotal = 3,
            bankerTotal = 2,
            isPlayerNatural = false,
            isBankerNatural = false,
            multiplier = 2.0,
            newBalance = 1_100L,
            jackpotPayout = jackpotPayout
        )

    private fun pushOutcome(side: Baccarat.Side): PlayOutcome.Push =
        PlayOutcome.Push(
            stake = 75L,
            side = side,
            playerCards = playerCards,
            bankerCards = bankerCards,
            playerTotal = 5,
            bankerTotal = 5,
            isPlayerNatural = false,
            isBankerNatural = false,
            newBalance = 1_000L
        )

    private fun loseOutcome(side: Baccarat.Side, tribute: Long): PlayOutcome.Lose =
        PlayOutcome.Lose(
            stake = 100L,
            side = side,
            winner = Baccarat.Side.BANKER,
            playerCards = playerCards,
            bankerCards = bankerCards,
            playerTotal = 3,
            bankerTotal = 7,
            isPlayerNatural = false,
            isBankerNatural = false,
            newBalance = 900L,
            lossTribute = tribute
        )
}
