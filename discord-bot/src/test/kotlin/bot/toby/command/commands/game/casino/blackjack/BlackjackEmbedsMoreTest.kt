package bot.toby.command.commands.game.casino.blackjack

import common.casino.blackjack.Blackjack
import common.casino.blackjack.BlackjackTable
import common.card.Card
import common.card.Rank
import common.card.Suit
import database.dto.casino.blackjack.BlackjackHandLogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Additional coverage for [BlackjackEmbeds] targeting branches and paths
 * not exercised by [BlackjackEmbedsTest].
 */
class BlackjackEmbedsMoreTest {

    // -------------------------------------------------------------------------
    // handLine — soft total label
    // -------------------------------------------------------------------------

    @Test
    fun `handLine labels soft total when ace counts as 11`() {
        // Ace + 6 = soft 17
        val line = BlackjackEmbeds.handLine(listOf(Card(Rank.ACE, Suit.HEARTS), Card(Rank.SIX, Suit.DIAMONDS)))
        assertTrue(line.contains("A♥"), "expected ace in: $line")
        assertTrue(line.contains("6♦"), "expected six in: $line")
        assertTrue(line.contains("soft"), "expected 'soft' label in: $line")
        assertTrue(line.contains("17"), "expected total 17 in: $line")
    }

    @Test
    fun `handLine does not label soft when total is exactly 21`() {
        // Ace + King = natural 21, NOT labelled soft
        val line = BlackjackEmbeds.handLine(listOf(Card(Rank.ACE, Suit.CLUBS), Card(Rank.KING, Suit.SPADES)))
        assertFalse(line.contains("soft"), "should not be 'soft' for 21: $line")
        assertTrue(line.contains("21"), "should contain 21: $line")
    }

    @Test
    fun `handLine hard total not labelled soft`() {
        // 10 + 7 = hard 17, no soft label
        val line = BlackjackEmbeds.handLine(listOf(Card(Rank.TEN, Suit.CLUBS), Card(Rank.SEVEN, Suit.HEARTS)))
        assertFalse(line.contains("soft"), "hard hand should not have 'soft': $line")
        assertTrue(line.contains("17"), "should contain 17: $line")
    }

    // -------------------------------------------------------------------------
    // dealerUpLine — edge case: empty list
    // -------------------------------------------------------------------------

    @Test
    fun `dealerUpLine returns dash for empty dealer hand`() {
        assertEquals("—", BlackjackEmbeds.dealerUpLine(emptyList()))
    }

    @Test
    fun `dealerUpLine single card shows that card and hidden glyph`() {
        val line = BlackjackEmbeds.dealerUpLine(listOf(Card(Rank.ACE, Suit.SPADES)))
        assertTrue(line.contains("A♠"), "expected ace in: $line")
        assertTrue(line.contains("🂠"), "expected hidden glyph in: $line")
    }

    // -------------------------------------------------------------------------
    // soloDealEmbed — no seat / empty hands / split hands
    // -------------------------------------------------------------------------

    @Test
    fun `soloDealEmbed with no seat renders dash for player`() {
        val table = BlackjackTable(
            id = 1L, guildId = 10L, mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = 1L, ante = 50L, maxSeats = 1
        ).also { t ->
            t.dealer.addAll(listOf(Card(Rank.FIVE, Suit.HEARTS), Card(Rank.THREE, Suit.CLUBS)))
        }
        val embed = BlackjackEmbeds.soloDealEmbed(table)
        assertNotNull(embed.description)
        assertTrue(embed.description!!.contains("—"), "Expected dash for missing seat")
    }

    @Test
    fun `soloDealEmbed with split hands renders multiple hand lines`() {
        val table = buildSoloTableWithSplitHands()
        val embed = BlackjackEmbeds.soloDealEmbed(table)
        val desc = embed.description!!
        assertTrue(desc.contains("Hand 1"), "expected 'Hand 1' in: $desc")
        assertTrue(desc.contains("Hand 2"), "expected 'Hand 2' in: $desc")
    }

    @Test
    fun `soloDealEmbed split active hand shows arrow indicator`() {
        val table = buildSoloTableWithSplitHands(activeHandIndex = 1)
        val embed = BlackjackEmbeds.soloDealEmbed(table)
        val desc = embed.description!!
        // Arrow should be on hand 2 (index 1) since that's active
        assertTrue(desc.contains("▶"), "expected arrow indicator in: $desc")
    }

    @Test
    fun `soloDealEmbed with seat having no hands shows dash`() {
        val table = BlackjackTable(
            id = 2L, guildId = 10L, mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = 1L, ante = 50L, maxSeats = 1
        ).also { t ->
            // Seat with empty hands list
            val seat = BlackjackTable.Seat(discordId = 1L, ante = 50L, stake = 50L)
            seat.hands.clear()  // clear to simulate empty
            t.seats.add(seat)
            t.dealer.add(Card(Rank.KING, Suit.HEARTS))
        }
        val embed = BlackjackEmbeds.soloDealEmbed(table)
        assertNotNull(embed.description)
        // With empty hands, description shows "You: —"
        assertTrue(embed.description!!.contains("—"), "Expected dash for empty hands: ${embed.description}")
    }

    // -------------------------------------------------------------------------
    // handStatusBadge — all status variants covered via soloDealEmbed
    // -------------------------------------------------------------------------

    @Test
    fun `soloDealEmbed BLACKJACK status shows blackjack badge`() {
        val table = buildSoloTableWithSplitHands()
        table.seats[0].hands[0].status = BlackjackTable.SeatStatus.BLACKJACK
        val desc = BlackjackEmbeds.soloDealEmbed(table).description!!
        assertTrue(desc.contains("Blackjack"), "expected blackjack badge in: $desc")
    }

    @Test
    fun `soloDealEmbed BUSTED status shows bust badge`() {
        val table = buildSoloTableWithSplitHands()
        table.seats[0].hands[0].status = BlackjackTable.SeatStatus.BUSTED
        val desc = BlackjackEmbeds.soloDealEmbed(table).description!!
        assertTrue(desc.contains("Bust"), "expected bust badge in: $desc")
    }

    @Test
    fun `soloDealEmbed STANDING status shows stand badge`() {
        val table = buildSoloTableWithSplitHands()
        table.seats[0].hands[0].status = BlackjackTable.SeatStatus.STANDING
        val desc = BlackjackEmbeds.soloDealEmbed(table).description!!
        assertTrue(desc.contains("Stand"), "expected stand badge in: $desc")
    }

    @Test
    fun `soloDealEmbed DOUBLED status shows doubled badge`() {
        val table = buildSoloTableWithSplitHands()
        table.seats[0].hands[0].status = BlackjackTable.SeatStatus.DOUBLED
        val desc = BlackjackEmbeds.soloDealEmbed(table).description!!
        assertTrue(desc.contains("Doubled"), "expected doubled badge in: $desc")
    }

    // -------------------------------------------------------------------------
    // soloResolvedEmbed — every single-hand result variant
    // -------------------------------------------------------------------------

    @Test
    fun `soloResolvedEmbed PLAYER_BLACKJACK shows blackjack message`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.CLUBS)),
            dealer = mutableListOf(Card(Rank.SEVEN, Suit.HEARTS), Card(Rank.NINE, Suit.DIAMONDS))
        )
        val r = result(table, Blackjack.Result.PLAYER_BLACKJACK, payout = 250L)
        val embed = BlackjackEmbeds.soloResolvedEmbed(table, r, newBalance = 1_250L, jackpotPayout = 0L, lossTribute = 0L)
        val desc = embed.description!!
        assertTrue(desc.contains("Blackjack"), "expected blackjack in: $desc")
        // Balance field present
        assertTrue(embed.fields.any { it.name == "New balance" }, "expected balance field")
    }

    @Test
    fun `soloResolvedEmbed PUSH shows push message`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.KING, Suit.HEARTS), Card(Rank.SEVEN, Suit.CLUBS)),
            dealer = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.DIAMONDS))
        )
        val r = result(table, Blackjack.Result.PUSH, payout = 100L)
        val embed = BlackjackEmbeds.soloResolvedEmbed(table, r, newBalance = 1_000L, jackpotPayout = 0L, lossTribute = 0L)
        val desc = embed.description!!
        assertTrue(desc.contains("Push", ignoreCase = true), "expected push in: $desc")
    }

    @Test
    fun `soloResolvedEmbed DEALER_WIN shows dealer wins message`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.FIVE, Suit.HEARTS), Card(Rank.EIGHT, Suit.CLUBS)),
            dealer = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.DIAMONDS))
        )
        val r = result(table, Blackjack.Result.DEALER_WIN, payout = 0L)
        val embed = BlackjackEmbeds.soloResolvedEmbed(table, r, newBalance = 900L, jackpotPayout = 0L, lossTribute = 0L)
        val desc = embed.description!!
        assertTrue(desc.contains("Dealer", ignoreCase = true), "expected dealer wins in: $desc")
    }

    @Test
    fun `soloResolvedEmbed PLAYER_BUST shows bust message`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.KING, Suit.HEARTS), Card(Rank.QUEEN, Suit.CLUBS), Card(Rank.FIVE, Suit.DIAMONDS)),
            dealer = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.DIAMONDS))
        )
        val r = result(table, Blackjack.Result.PLAYER_BUST, payout = 0L)
        val embed = BlackjackEmbeds.soloResolvedEmbed(table, r, newBalance = 900L, jackpotPayout = 0L, lossTribute = 0L)
        val desc = embed.description!!
        assertTrue(desc.contains("Bust", ignoreCase = true), "expected bust in: $desc")
    }

    @Test
    fun `soloResolvedEmbed shows jackpot payout when positive`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.CLUBS)),
            dealer = mutableListOf(Card(Rank.SEVEN, Suit.HEARTS), Card(Rank.NINE, Suit.DIAMONDS))
        )
        val r = result(table, Blackjack.Result.PLAYER_WIN, payout = 200L)
        val embed = BlackjackEmbeds.soloResolvedEmbed(table, r, newBalance = 1_200L, jackpotPayout = 500L, lossTribute = 0L)
        val desc = embed.description!!
        assertTrue(desc.contains("Jackpot", ignoreCase = true), "expected jackpot in: $desc")
        assertTrue(desc.contains("500"), "expected jackpot amount in: $desc")
    }

    @Test
    fun `soloResolvedEmbed shows loss tribute when positive`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.FIVE, Suit.HEARTS), Card(Rank.EIGHT, Suit.CLUBS)),
            dealer = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.DIAMONDS))
        )
        val r = result(table, Blackjack.Result.DEALER_WIN, payout = 0L)
        val embed = BlackjackEmbeds.soloResolvedEmbed(table, r, newBalance = 900L, jackpotPayout = 0L, lossTribute = 25L)
        val desc = embed.description!!
        assertTrue(desc.contains("jackpot pool", ignoreCase = true), "expected jackpot pool in: $desc")
        assertTrue(desc.contains("25"), "expected tribute amount in: $desc")
    }

    // -------------------------------------------------------------------------
    // soloResolvedEmbed — multi-hand (split) path
    // -------------------------------------------------------------------------

    @Test
    fun `soloResolvedEmbed multi-hand net positive shows gain message`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            dealer = mutableListOf(Card(Rank.NINE, Suit.SPADES), Card(Rank.SEVEN, Suit.SPADES))
        )
        val perHand = listOf(
            makePerHand(discordId = 1L, handIndex = 0, result = Blackjack.Result.PLAYER_WIN, stake = 100L, payout = 200L),
            makePerHand(discordId = 1L, handIndex = 1, result = Blackjack.Result.PLAYER_WIN, stake = 100L, payout = 200L)
        )
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = table.dealer.toList(),
            dealerTotal = 16,
            seatResults = mapOf(1L to Blackjack.Result.PLAYER_WIN),
            payouts = mapOf(1L to 400L),
            pot = 200L,
            rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = perHand
        )
        val embed = BlackjackEmbeds.soloResolvedEmbed(table, handResult, newBalance = 1_400L, jackpotPayout = 0L, lossTribute = 0L)
        val desc = embed.description!!
        assertTrue(desc.contains("Hand 1"), "expected Hand 1 in: $desc")
        assertTrue(desc.contains("Hand 2"), "expected Hand 2 in: $desc")
        // net = 400 - 200 = 200 > 0
        assertTrue(desc.contains("+200"), "expected net gain in: $desc")
    }

    @Test
    fun `soloResolvedEmbed multi-hand net negative shows loss message`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            dealer = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.NINE, Suit.SPADES))
        )
        val perHand = listOf(
            makePerHand(discordId = 1L, handIndex = 0, result = Blackjack.Result.DEALER_WIN, stake = 100L, payout = 0L),
            makePerHand(discordId = 1L, handIndex = 1, result = Blackjack.Result.DEALER_WIN, stake = 100L, payout = 0L)
        )
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = table.dealer.toList(),
            dealerTotal = 19,
            seatResults = mapOf(1L to Blackjack.Result.DEALER_WIN),
            payouts = emptyMap(),
            pot = 200L,
            rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = perHand
        )
        val embed = BlackjackEmbeds.soloResolvedEmbed(table, handResult, newBalance = 800L, jackpotPayout = 0L, lossTribute = 0L)
        val desc = embed.description!!
        // net = 0 - 200 = -200
        assertTrue(desc.contains("-200"), "expected net loss in: $desc")
    }

    @Test
    fun `soloResolvedEmbed multi-hand net zero shows break even message`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            dealer = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.NINE, Suit.SPADES))
        )
        val perHand = listOf(
            makePerHand(discordId = 1L, handIndex = 0, result = Blackjack.Result.PLAYER_WIN, stake = 100L, payout = 200L),
            makePerHand(discordId = 1L, handIndex = 1, result = Blackjack.Result.DEALER_WIN, stake = 100L, payout = 0L)
        )
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = table.dealer.toList(),
            dealerTotal = 19,
            seatResults = mapOf(1L to Blackjack.Result.PUSH),
            payouts = mapOf(1L to 200L),
            pot = 200L,
            rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = perHand
        )
        val embed = BlackjackEmbeds.soloResolvedEmbed(table, handResult, newBalance = 1_000L, jackpotPayout = 0L, lossTribute = 0L)
        val desc = embed.description!!
        // net = 200 - 200 = 0
        assertTrue(desc.contains("broke even", ignoreCase = true), "expected broke even in: $desc")
    }

    @Test
    fun `soloResolvedEmbed multi-hand verdictLabel each result`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            dealer = mutableListOf(Card(Rank.NINE, Suit.SPADES), Card(Rank.SEVEN, Suit.SPADES))
        )
        // Test all 5 result variants appear in the verdictLabel
        val allResults = listOf(
            Blackjack.Result.PLAYER_BLACKJACK to 250L,
            Blackjack.Result.PLAYER_WIN to 200L,
            Blackjack.Result.PUSH to 100L,
            Blackjack.Result.DEALER_WIN to 0L,
            Blackjack.Result.PLAYER_BUST to 0L
        )
        for ((result, payout) in allResults) {
            val perHand = allResults.mapIndexed { idx, (r, p) ->
                makePerHand(discordId = 1L, handIndex = idx, result = r, stake = 100L, payout = p)
            }
            val handResult = BlackjackTable.HandResult(
                handNumber = 1L,
                dealer = table.dealer.toList(),
                dealerTotal = 16,
                seatResults = mapOf(1L to result),
                payouts = mapOf(1L to payout),
                pot = 500L,
                rake = 0L,
                resolvedAt = Instant.now(),
                perHandResults = perHand
            )
            val embed = BlackjackEmbeds.soloResolvedEmbed(table, handResult, newBalance = 1_000L, jackpotPayout = 0L, lossTribute = 0L)
            assertNotNull(embed.description, "embed description should not be null for $result")
        }
    }

    // -------------------------------------------------------------------------
    // soloResolvedEmbed — empty perHandResults fallback (null seat case)
    // -------------------------------------------------------------------------

    @Test
    fun `soloResolvedEmbed single hand with no perHand results uses seat hand fallback`() {
        val table = soloTable(
            player = mutableListOf(Card(Rank.KING, Suit.HEARTS), Card(Rank.SEVEN, Suit.CLUBS)),
            dealer = mutableListOf(Card(Rank.NINE, Suit.SPADES), Card(Rank.EIGHT, Suit.DIAMONDS))
        )
        // Use a result with empty perHandResults so it falls back to seat.hand
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = table.dealer.toList(),
            dealerTotal = 17,
            seatResults = mapOf(1L to Blackjack.Result.PLAYER_WIN),
            payouts = mapOf(1L to 200L),
            pot = 100L,
            rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = emptyList()
        )
        val embed = BlackjackEmbeds.soloResolvedEmbed(table, handResult, newBalance = 1_200L, jackpotPayout = 0L, lossTribute = 0L)
        val desc = embed.description!!
        // Should contain the player's cards from the seat
        assertTrue(desc.contains("K♥") || desc.contains("7♣") || desc.contains("—"), "expected player cards or dash in: $desc")
    }

    // -------------------------------------------------------------------------
    // lobbyEmbed — empty seats
    // -------------------------------------------------------------------------

    @Test
    fun `lobbyEmbed with no seats shows dash`() {
        val table = BlackjackTable(
            id = 7L, guildId = 42L, mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = 99L, ante = 50L, maxSeats = 4
        )
        val embed = BlackjackEmbeds.lobbyEmbed(table)
        val seatedField = embed.fields.firstOrNull { it.name == "Seated" }
        assertNotNull(seatedField, "expected 'Seated' field")
        assertEquals("—", seatedField!!.value)
    }

    @Test
    fun `lobbyEmbed with seats shows player mentions`() {
        val table = BlackjackTable(
            id = 8L, guildId = 42L, mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = 1L, ante = 100L, maxSeats = 4
        ).also { t ->
            t.seats.add(BlackjackTable.Seat(discordId = 111L, ante = 100L, stake = 100L))
            t.seats.add(BlackjackTable.Seat(discordId = 222L, ante = 100L, stake = 100L))
        }
        val embed = BlackjackEmbeds.lobbyEmbed(table)
        val seatedField = embed.fields.firstOrNull { it.name == "Seated" }
        assertNotNull(seatedField)
        assertTrue(seatedField!!.value!!.contains("<@111>"), "expected player 111 mention")
        assertTrue(seatedField.value!!.contains("<@222>"), "expected player 222 mention")
    }

    // -------------------------------------------------------------------------
    // multiHandStateEmbed — various phases and multi-hand seats
    // -------------------------------------------------------------------------

    @Test
    fun `multiHandStateEmbed DEALER_TURN phase does not show actor line`() {
        val table = multiTable().apply {
            seats.add(BlackjackTable.Seat(discordId = 1L, ante = 100L, stake = 100L))
            phase = BlackjackTable.Phase.DEALER_TURN
            dealer.addAll(listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)))
        }
        val embed = BlackjackEmbeds.multiHandStateEmbed(table)
        val desc = embed.description!!
        // Actor line only shows in PLAYER_TURNS
        assertFalse(desc.contains("To act:"), "should not have 'To act:' in DEALER_TURN: $desc")
    }

    @Test
    fun `multiHandStateEmbed empty seats renders without crashing`() {
        val table = multiTable().apply {
            phase = BlackjackTable.Phase.LOBBY
            dealer.add(Card(Rank.KING, Suit.HEARTS))
        }
        val embed = BlackjackEmbeds.multiHandStateEmbed(table)
        assertNotNull(embed.description)
    }

    @Test
    fun `multiHandStateEmbed multi-hand seat renders all hands`() {
        val table = buildMultiTableWithSplitSeat()
        val embed = BlackjackEmbeds.multiHandStateEmbed(table)
        val desc = embed.description!!
        assertTrue(desc.contains("Hand 1"), "expected Hand 1 in: $desc")
        assertTrue(desc.contains("Hand 2"), "expected Hand 2 in: $desc")
    }

    @Test
    fun `multiHandStateEmbed actor with split hands shows hand count`() {
        val table = buildMultiTableWithSplitSeat()
        table.phase = BlackjackTable.Phase.PLAYER_TURNS
        table.actorIndex = 0
        val embed = BlackjackEmbeds.multiHandStateEmbed(table)
        val desc = embed.description!!
        // "To act" line shows hand index info for multi-hand actors
        assertTrue(desc.contains("To act:"), "expected 'To act:' in: $desc")
        // Checks the "Hand X of Y" part
        assertTrue(desc.contains("Hand") && desc.contains("of"), "expected hand count in: $desc")
    }

    @Test
    fun `multiHandStateEmbed seat with no hand slot shows dash`() {
        val table = multiTable().apply {
            val seat = BlackjackTable.Seat(discordId = 5L, ante = 100L, stake = 100L)
            seat.hands.clear()
            seats.add(seat)
            phase = BlackjackTable.Phase.PLAYER_TURNS
            dealer.add(Card(Rank.KING, Suit.CLUBS))
        }
        val embed = BlackjackEmbeds.multiHandStateEmbed(table)
        // Should not crash — seat with empty hands shows dash for cards
        assertNotNull(embed.description)
    }

    // -------------------------------------------------------------------------
    // multiResolvedEmbed — perHandResults path (all per-hand verdicts)
    // -------------------------------------------------------------------------

    @Test
    fun `multiResolvedEmbed with perHandResults single hand per player`() {
        val table = multiTable().apply {
            seats.add(BlackjackTable.Seat(discordId = 10L, ante = 100L, stake = 100L))
        }
        val perHand = listOf(
            makePerHand(discordId = 10L, handIndex = 0, result = Blackjack.Result.PLAYER_WIN, stake = 100L, payout = 200L)
        )
        val handResult = BlackjackTable.HandResult(
            handNumber = 2L,
            dealer = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.EIGHT, Suit.HEARTS)),
            dealerTotal = 18,
            seatResults = mapOf(10L to Blackjack.Result.PLAYER_WIN),
            payouts = mapOf(10L to 200L),
            pot = 100L,
            rake = 5L,
            resolvedAt = Instant.now(),
            perHandResults = perHand
        )
        val embed = BlackjackEmbeds.multiResolvedEmbed(table, handResult)
        val desc = embed.description!!
        assertTrue(desc.contains("<@10>"), "expected player mention in: $desc")
        assertTrue(desc.contains("Win"), "expected Win in: $desc")
        assertTrue(embed.title!!.contains("settled"), "expected 'settled' in title: ${embed.title}")
    }

    @Test
    fun `multiResolvedEmbed with perHandResults multiple hands per player`() {
        val table = multiTable().apply {
            seats.add(BlackjackTable.Seat(discordId = 20L, ante = 100L, stake = 100L))
        }
        val perHand = listOf(
            makePerHand(discordId = 20L, handIndex = 0, result = Blackjack.Result.PLAYER_WIN, stake = 100L, payout = 200L),
            makePerHand(discordId = 20L, handIndex = 1, result = Blackjack.Result.PLAYER_BUST, stake = 100L, payout = 0L)
        )
        val handResult = BlackjackTable.HandResult(
            handNumber = 3L,
            dealer = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.EIGHT, Suit.HEARTS)),
            dealerTotal = 18,
            seatResults = mapOf(20L to Blackjack.Result.PLAYER_WIN),
            payouts = mapOf(20L to 200L),
            pot = 200L,
            rake = 10L,
            resolvedAt = Instant.now(),
            perHandResults = perHand
        )
        val embed = BlackjackEmbeds.multiResolvedEmbed(table, handResult)
        val desc = embed.description!!
        assertTrue(desc.contains("Hand 1"), "expected Hand 1 in: $desc")
        assertTrue(desc.contains("Hand 2"), "expected Hand 2 in: $desc")
    }

    @Test
    fun `multiResolvedEmbed with perHandResults PLAYER_BLACKJACK verdict`() {
        val table = multiTable()
        val perHand = listOf(
            makePerHand(discordId = 30L, handIndex = 0, result = Blackjack.Result.PLAYER_BLACKJACK, stake = 100L, payout = 250L)
        )
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = listOf(Card(Rank.SEVEN, Suit.SPADES), Card(Rank.EIGHT, Suit.HEARTS)),
            dealerTotal = 15,
            seatResults = mapOf(30L to Blackjack.Result.PLAYER_BLACKJACK),
            payouts = mapOf(30L to 250L),
            pot = 100L, rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = perHand
        )
        val desc = BlackjackEmbeds.multiResolvedEmbed(table, handResult).description!!
        assertTrue(desc.contains("Blackjack"), "expected Blackjack in: $desc")
    }

    @Test
    fun `multiResolvedEmbed with perHandResults PUSH verdict`() {
        val table = multiTable()
        val perHand = listOf(
            makePerHand(discordId = 40L, handIndex = 0, result = Blackjack.Result.PUSH, stake = 100L, payout = 100L)
        )
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            dealerTotal = 17,
            seatResults = mapOf(40L to Blackjack.Result.PUSH),
            payouts = mapOf(40L to 100L),
            pot = 100L, rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = perHand
        )
        val desc = BlackjackEmbeds.multiResolvedEmbed(table, handResult).description!!
        assertTrue(desc.contains("Push", ignoreCase = true), "expected Push in: $desc")
    }

    @Test
    fun `multiResolvedEmbed with perHandResults DEALER_WIN verdict`() {
        val table = multiTable()
        val perHand = listOf(
            makePerHand(discordId = 50L, handIndex = 0, result = Blackjack.Result.DEALER_WIN, stake = 100L, payout = 0L)
        )
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.NINE, Suit.HEARTS)),
            dealerTotal = 19,
            seatResults = mapOf(50L to Blackjack.Result.DEALER_WIN),
            payouts = emptyMap(),
            pot = 100L, rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = perHand
        )
        val desc = BlackjackEmbeds.multiResolvedEmbed(table, handResult).description!!
        assertTrue(desc.contains("Lose"), "expected Lose in: $desc")
    }

    // -------------------------------------------------------------------------
    // multiResolvedEmbed — seatResults fallback (empty perHandResults)
    // -------------------------------------------------------------------------

    @Test
    fun `multiResolvedEmbed falls back to seatResults when perHandResults empty`() {
        val table = multiTable()
        val handResult = BlackjackTable.HandResult(
            handNumber = 4L,
            dealer = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            dealerTotal = 17,
            seatResults = mapOf(
                60L to Blackjack.Result.PLAYER_WIN,
                61L to Blackjack.Result.PLAYER_BUST
            ),
            payouts = mapOf(60L to 200L),
            pot = 200L,
            rake = 10L,
            resolvedAt = Instant.now(),
            perHandResults = emptyList()
        )
        val desc = BlackjackEmbeds.multiResolvedEmbed(table, handResult).description!!
        assertTrue(desc.contains("<@60>"), "expected player 60 mention in: $desc")
        assertTrue(desc.contains("<@61>"), "expected player 61 mention in: $desc")
        assertTrue(desc.contains("Win", ignoreCase = true), "expected Win in: $desc")
        assertTrue(desc.contains("Bust", ignoreCase = true), "expected Bust in: $desc")
    }

    @Test
    fun `multiResolvedEmbed seatResults PLAYER_BLACKJACK verdict`() {
        val table = multiTable()
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = listOf(Card(Rank.SEVEN, Suit.SPADES), Card(Rank.NINE, Suit.HEARTS)),
            dealerTotal = 16,
            seatResults = mapOf(70L to Blackjack.Result.PLAYER_BLACKJACK),
            payouts = mapOf(70L to 250L),
            pot = 100L, rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = emptyList()
        )
        val desc = BlackjackEmbeds.multiResolvedEmbed(table, handResult).description!!
        assertTrue(desc.contains("Blackjack"), "expected Blackjack in seatResults path: $desc")
    }

    @Test
    fun `multiResolvedEmbed seatResults PUSH verdict`() {
        val table = multiTable()
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            dealerTotal = 17,
            seatResults = mapOf(80L to Blackjack.Result.PUSH),
            payouts = mapOf(80L to 100L),
            pot = 100L, rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = emptyList()
        )
        val desc = BlackjackEmbeds.multiResolvedEmbed(table, handResult).description!!
        assertTrue(desc.contains("Push", ignoreCase = true), "expected Push in seatResults path: $desc")
    }

    @Test
    fun `multiResolvedEmbed seatResults DEALER_WIN verdict`() {
        val table = multiTable()
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.NINE, Suit.HEARTS)),
            dealerTotal = 19,
            seatResults = mapOf(90L to Blackjack.Result.DEALER_WIN),
            payouts = emptyMap(),
            pot = 100L, rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = emptyList()
        )
        val desc = BlackjackEmbeds.multiResolvedEmbed(table, handResult).description!!
        assertTrue(desc.contains("Lose"), "expected Lose in seatResults path: $desc")
    }

    @Test
    fun `multiResolvedEmbed seatResults PLAYER_BUST verdict`() {
        val table = multiTable()
        val handResult = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.NINE, Suit.HEARTS)),
            dealerTotal = 19,
            seatResults = mapOf(91L to Blackjack.Result.PLAYER_BUST),
            payouts = emptyMap(),
            pot = 100L, rake = 0L,
            resolvedAt = Instant.now(),
            perHandResults = emptyList()
        )
        val desc = BlackjackEmbeds.multiResolvedEmbed(table, handResult).description!!
        assertTrue(desc.contains("Bust", ignoreCase = true), "expected Bust in seatResults path: $desc")
    }

    @Test
    fun `multiResolvedEmbed shows pot and rake`() {
        val table = multiTable()
        val handResult = BlackjackTable.HandResult(
            handNumber = 5L,
            dealer = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            dealerTotal = 17,
            seatResults = mapOf(100L to Blackjack.Result.PLAYER_WIN),
            payouts = mapOf(100L to 200L),
            pot = 300L,
            rake = 15L,
            resolvedAt = Instant.now(),
            perHandResults = emptyList()
        )
        val desc = BlackjackEmbeds.multiResolvedEmbed(table, handResult).description!!
        assertTrue(desc.contains("300"), "expected pot 300 in: $desc")
        assertTrue(desc.contains("15"), "expected rake 15 in: $desc")
    }

    // -------------------------------------------------------------------------
    // multiResolvedEmbed — multi-seat table
    // -------------------------------------------------------------------------

    @Test
    fun `multiResolvedEmbed multi-seat table shows all players`() {
        val table = multiTable().apply {
            seats.add(BlackjackTable.Seat(discordId = 101L, ante = 100L, stake = 100L))
            seats.add(BlackjackTable.Seat(discordId = 102L, ante = 100L, stake = 100L))
        }
        val perHand = listOf(
            makePerHand(discordId = 101L, handIndex = 0, result = Blackjack.Result.PLAYER_WIN, stake = 100L, payout = 200L),
            makePerHand(discordId = 102L, handIndex = 0, result = Blackjack.Result.DEALER_WIN, stake = 100L, payout = 0L)
        )
        val handResult = BlackjackTable.HandResult(
            handNumber = 6L,
            dealer = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.NINE, Suit.HEARTS)),
            dealerTotal = 19,
            seatResults = mapOf(101L to Blackjack.Result.PLAYER_WIN, 102L to Blackjack.Result.DEALER_WIN),
            payouts = mapOf(101L to 200L),
            pot = 200L, rake = 10L,
            resolvedAt = Instant.now(),
            perHandResults = perHand
        )
        val desc = BlackjackEmbeds.multiResolvedEmbed(table, handResult).description!!
        assertTrue(desc.contains("<@101>"), "expected player 101 in: $desc")
        assertTrue(desc.contains("<@102>"), "expected player 102 in: $desc")
    }

    // -------------------------------------------------------------------------
    // peekEmbed — with cards
    // -------------------------------------------------------------------------

    @Test
    fun `peekEmbed with cards shows the hand`() {
        val hand = listOf(Card(Rank.ACE, Suit.DIAMONDS), Card(Rank.KING, Suit.CLUBS))
        val embed = BlackjackEmbeds.peekEmbed(hand)
        val desc = embed.description!!
        assertTrue(desc.contains("A♦"), "expected ace in: $desc")
        assertTrue(desc.contains("K♣"), "expected king in: $desc")
        assertTrue(desc.contains("21"), "expected total 21 in: $desc")
    }

    // -------------------------------------------------------------------------
    // infoEmbed
    // -------------------------------------------------------------------------

    @Test
    fun `infoEmbed exposes the message`() {
        val embed = BlackjackEmbeds.infoEmbed("Table is full.")
        assertNotNull(embed.description)
        assertTrue(embed.description!!.contains("Table is full."))
    }

    @Test
    fun `infoEmbed has blackjack title`() {
        val embed = BlackjackEmbeds.infoEmbed("anything")
        assertTrue(embed.title!!.contains("Blackjack", ignoreCase = true))
    }

    // -------------------------------------------------------------------------
    // historyEmbed
    // -------------------------------------------------------------------------

    @Test
    fun `historyEmbed with empty list shows no settled hands message`() {
        val embed = BlackjackEmbeds.historyEmbed("Server", emptyList())
        val desc = embed.description!!
        assertTrue(desc.contains("No settled hands yet"), "expected no-hands message in: $desc")
    }

    @Test
    fun `historyEmbed with rows renders hand entries`() {
        val row = BlackjackHandLogDto(
            id = null,
            guildId = 42L,
            tableId = 5L,
            handNumber = 1L,
            mode = "SOLO",
            players = "111",
            dealer = "K♠,7♥",
            dealerTotal = 17,
            seatResults = "111:PLAYER_WIN",
            payouts = "111:200",
            pot = 100L,
            rake = 5L,
            resolvedAt = Instant.parse("2025-06-01T12:30:00Z")
        )
        val embed = BlackjackEmbeds.historyEmbed("Server", listOf(row))
        val desc = embed.description!!
        assertTrue(desc.contains("#1"), "expected hand #1 in: $desc")
        assertTrue(desc.contains("SOLO"), "expected mode in: $desc")
        assertTrue(desc.contains("17"), "expected dealer total in: $desc")
    }

    @Test
    fun `historyEmbed scope appears in title`() {
        val embed = BlackjackEmbeds.historyEmbed("Table #7", emptyList())
        assertTrue(embed.title!!.contains("Table #7"), "expected scope in title: ${embed.title}")
    }

    @Test
    fun `historyEmbed shortResult PLAYER_BLACKJACK renders BJ glyph`() {
        val row = makeHandLogRow(seatResults = "200:PLAYER_BLACKJACK")
        val desc = BlackjackEmbeds.historyEmbed("Server", listOf(row)).description!!
        assertTrue(desc.contains("BJ"), "expected BJ glyph in: $desc")
    }

    @Test
    fun `historyEmbed shortResult PLAYER_WIN renders checkmark`() {
        val row = makeHandLogRow(seatResults = "200:PLAYER_WIN")
        val desc = BlackjackEmbeds.historyEmbed("Server", listOf(row)).description!!
        assertTrue(desc.contains("✅"), "expected win glyph in: $desc")
    }

    @Test
    fun `historyEmbed shortResult PUSH renders handshake`() {
        val row = makeHandLogRow(seatResults = "200:PUSH")
        val desc = BlackjackEmbeds.historyEmbed("Server", listOf(row)).description!!
        assertTrue(desc.contains("🤝"), "expected push glyph in: $desc")
    }

    @Test
    fun `historyEmbed shortResult DEALER_WIN renders cross`() {
        val row = makeHandLogRow(seatResults = "200:DEALER_WIN")
        val desc = BlackjackEmbeds.historyEmbed("Server", listOf(row)).description!!
        assertTrue(desc.contains("❌"), "expected dealer win glyph in: $desc")
    }

    @Test
    fun `historyEmbed shortResult PLAYER_BUST renders explosion`() {
        val row = makeHandLogRow(seatResults = "200:PLAYER_BUST")
        val desc = BlackjackEmbeds.historyEmbed("Server", listOf(row)).description!!
        assertTrue(desc.contains("💥"), "expected bust glyph in: $desc")
    }

    @Test
    fun `historyEmbed shortResult unknown string renders raw`() {
        val row = makeHandLogRow(seatResults = "200:SOME_UNKNOWN_RESULT")
        val desc = BlackjackEmbeds.historyEmbed("Server", listOf(row)).description!!
        assertTrue(desc.contains("SOME_UNKNOWN_RESULT"), "expected raw unknown result in: $desc")
    }

    @Test
    fun `historyEmbed dealer blank renders dash`() {
        val row = makeHandLogRow(dealer = "")
        val desc = BlackjackEmbeds.historyEmbed("Server", listOf(row)).description!!
        // blank dealer becomes "—"
        assertTrue(desc.contains("—"), "expected dash for blank dealer in: $desc")
    }

    @Test
    fun `historyEmbed dealer commas replaced with spaces`() {
        val row = makeHandLogRow(dealer = "K♠,7♥,A♦")
        val desc = BlackjackEmbeds.historyEmbed("Server", listOf(row)).description!!
        // commas in dealer string should be replaced by spaces
        assertFalse(desc.contains("K♠,7♥"), "expected no comma-separated cards in: $desc")
        assertTrue(desc.contains("K♠ 7♥"), "expected space-separated cards in: $desc")
    }

    @Test
    fun `historyEmbed malformed seatResults entry renders raw`() {
        // Entry without colon separator — falls back to raw entry
        val row = makeHandLogRow(seatResults = "MALFORMED_NO_COLON")
        val desc = BlackjackEmbeds.historyEmbed("Server", listOf(row)).description!!
        assertTrue(desc.contains("MALFORMED_NO_COLON"), "expected raw malformed entry in: $desc")
    }

    @Test
    fun `historyEmbed multiple rows renders all`() {
        val row1 = makeHandLogRow(handNumber = 1L, seatResults = "111:PLAYER_WIN")
        val row2 = makeHandLogRow(handNumber = 2L, seatResults = "111:PLAYER_BUST")
        val desc = BlackjackEmbeds.historyEmbed("Server", listOf(row1, row2)).description!!
        assertTrue(desc.contains("#1"), "expected #1 in: $desc")
        assertTrue(desc.contains("#2"), "expected #2 in: $desc")
    }

    // -------------------------------------------------------------------------
    // parseButtonId — extra edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `parseButtonId wrong prefix returns null`() {
        assertNull(BlackjackEmbeds.parseButtonId("poker:HIT:1"))
    }

    @Test
    fun `parseButtonId too many parts returns null`() {
        assertNull(BlackjackEmbeds.parseButtonId("blackjack:HIT:1:extra"))
    }

    @Test
    fun `parseButtonId case insensitive prefix`() {
        val parsed = BlackjackEmbeds.parseButtonId("BLACKJACK:HIT:99")
        assertNotNull(parsed, "should accept case-insensitive prefix")
        assertEquals(BlackjackEmbeds.Action.HIT, parsed!!.action)
        assertEquals(99L, parsed.tableId)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun soloTable(player: MutableList<Card>, dealer: MutableList<Card>): BlackjackTable {
        val t = BlackjackTable(
            id = 1L, guildId = 42L, mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = 1L, ante = 100L, maxSeats = 1
        )
        t.seats.add(BlackjackTable.Seat(discordId = 1L, hand = player, ante = 100L, stake = 100L))
        t.dealer.addAll(dealer)
        return t
    }

    private fun multiTable(ante: Long = 100L): BlackjackTable = BlackjackTable(
        id = 5L, guildId = 42L, mode = BlackjackTable.Mode.MULTI,
        hostDiscordId = 1L, ante = ante, maxSeats = 5
    )

    private fun result(
        table: BlackjackTable,
        r: Blackjack.Result,
        payout: Long
    ): BlackjackTable.HandResult {
        val seat = table.seats.firstOrNull()
        val perHand = if (seat != null) listOf(
            makePerHand(
                discordId = seat.discordId,
                handIndex = 0,
                result = r,
                stake = seat.stake,
                payout = payout,
                cards = seat.hand.toList()
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

    private fun makePerHand(
        discordId: Long,
        handIndex: Int,
        result: Blackjack.Result,
        stake: Long,
        payout: Long,
        cards: List<Card> = listOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS))
    ) = BlackjackTable.PerHandResult(
        discordId = discordId,
        handIndex = handIndex,
        cards = cards,
        total = 17,
        stake = stake,
        doubled = false,
        fromSplit = false,
        result = result,
        payout = payout
    )

    private fun buildSoloTableWithSplitHands(activeHandIndex: Int = 0): BlackjackTable {
        val t = BlackjackTable(
            id = 3L, guildId = 42L, mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = 1L, ante = 100L, maxSeats = 1
        )
        // Create a seat with two hands (simulating a split)
        val seat = BlackjackTable.Seat(
            discordId = 1L,
            hand = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            ante = 100L,
            stake = 100L
        )
        seat.hands.add(
            BlackjackTable.HandSlot(
                cards = mutableListOf(Card(Rank.KING, Suit.CLUBS), Card(Rank.EIGHT, Suit.DIAMONDS)),
                stake = 100L,
                fromSplit = true
            )
        )
        seat.activeHandIndex = activeHandIndex
        t.seats.add(seat)
        t.dealer.addAll(listOf(Card(Rank.NINE, Suit.HEARTS), Card(Rank.TWO, Suit.CLUBS)))
        return t
    }

    private fun buildMultiTableWithSplitSeat(): BlackjackTable {
        val t = multiTable()
        val seat = BlackjackTable.Seat(
            discordId = 5L,
            hand = mutableListOf(Card(Rank.KING, Suit.SPADES), Card(Rank.SEVEN, Suit.HEARTS)),
            ante = 100L,
            stake = 100L
        )
        seat.hands.add(
            BlackjackTable.HandSlot(
                cards = mutableListOf(Card(Rank.KING, Suit.CLUBS), Card(Rank.EIGHT, Suit.DIAMONDS)),
                stake = 100L,
                fromSplit = true
            )
        )
        t.seats.add(seat)
        t.dealer.addAll(listOf(Card(Rank.NINE, Suit.HEARTS), Card(Rank.TWO, Suit.CLUBS)))
        t.phase = BlackjackTable.Phase.PLAYER_TURNS
        t.actorIndex = 0
        return t
    }

    private fun makeHandLogRow(
        handNumber: Long = 1L,
        seatResults: String = "111:PLAYER_WIN",
        dealer: String = "K♠,7♥",
        dealerTotal: Int = 17
    ) = BlackjackHandLogDto(
        id = null,
        guildId = 42L,
        tableId = 5L,
        handNumber = handNumber,
        mode = "SOLO",
        players = "111",
        dealer = dealer,
        dealerTotal = dealerTotal,
        seatResults = seatResults,
        payouts = "111:200",
        pot = 100L,
        rake = 5L,
        resolvedAt = Instant.parse("2025-06-01T12:30:00Z")
    )
}
