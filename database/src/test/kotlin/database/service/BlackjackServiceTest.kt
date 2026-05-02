package database.service

import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.blackjack.bestTotal
import database.card.Card
import database.card.Deck
import database.card.Rank
import database.card.Suit
import database.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random

class BlackjackServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService
    private lateinit var registry: BlackjackTableRegistry
    private lateinit var blackjack: Blackjack
    private lateinit var service: BlackjackService

    private val guildId = 200L
    private val discordId = 100L
    private val otherDiscordId = 101L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        registry = BlackjackTableRegistry(
            idleTtl = Duration.ofMinutes(5),
            sweepInterval = Duration.ofHours(1)
        )
        blackjack = mockk(relaxed = true)
        // Default: deck creation always succeeds; service code calls newDeck()
        // and stashes the result on the table — we don't care which deck it is.
        every { blackjack.newDeck() } returns Deck(Random(0))
        every { blackjack.multiplier(any(), any()) } answers { realMultiplier(arg(0)) }
        service = BlackjackService(
            userService = userService,
            jackpotService = jackpotService,
            configService = configService,
            tableRegistry = registry,
            blackjack = blackjack,
            random = Random(0)
        )
    }

    private fun userWithBalance(balance: Long, id: Long = discordId): UserDto =
        UserDto(id, guildId).apply { socialCredit = balance }

    private fun c(rank: Rank, suit: Suit = Suit.SPADES) = Card(rank, suit)

    private fun realMultiplier(result: Blackjack.Result): Double = when (result) {
        Blackjack.Result.PLAYER_BLACKJACK -> 2.5
        Blackjack.Result.PLAYER_WIN -> 2.0
        Blackjack.Result.PUSH -> 1.0
        Blackjack.Result.DEALER_WIN, Blackjack.Result.PLAYER_BUST -> 0.0
    }

    // -------------------------------------------------------------------------
    // SOLO
    // -------------------------------------------------------------------------

    @Test
    fun `dealSolo InvalidStake when stake out of bounds`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns userWithBalance(1_000L)
        val outcome = service.dealSolo(discordId, guildId, stake = 1L)
        assertInstanceOf(BlackjackService.SoloDealOutcome.InvalidStake::class.java, outcome)
    }

    @Test
    fun `dealSolo InsufficientCredits when balance is short`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns userWithBalance(5L)
        val outcome = service.dealSolo(discordId, guildId, stake = 50L)
        val insuff = assertInstanceOf(BlackjackService.SoloDealOutcome.InsufficientCredits::class.java, outcome)
        assertEquals(50L, insuff.stake)
        assertEquals(5L, insuff.have)
    }

    @Test
    fun `dealSolo UnknownUser when user not found`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null
        val outcome = service.dealSolo(discordId, guildId, stake = 50L)
        assertEquals(BlackjackService.SoloDealOutcome.UnknownUser, outcome)
    }

    @Test
    fun `dealSolo with non-blackjack deal returns Dealt and debits stake`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // 8-9 vs dealer 10-? — neither side has BJ.
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.EIGHT), c(Rank.NINE)),
            mutableListOf(c(Rank.KING), c(Rank.FOUR))
        )
        val outcome = service.dealSolo(discordId, guildId, stake = 50L)
        val dealt = assertInstanceOf(BlackjackService.SoloDealOutcome.Dealt::class.java, outcome)
        assertEquals(950L, user.socialCredit, "stake debited from balance")
        val table = registry.get(dealt.tableId)!!
        assertEquals(BlackjackTable.Phase.PLAYER_TURNS, table.phase)
        assertEquals(1, table.seats.size)
        assertEquals(50L, table.seats[0].stake)
    }

    @Test
    fun `dealSolo with player blackjack auto-resolves 2_5x payout`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.ACE), c(Rank.KING)), // BJ
            mutableListOf(c(Rank.NINE), c(Rank.SEVEN)) // 16
        )
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_BLACKJACK

        val outcome = service.dealSolo(discordId, guildId, stake = 100L)
        val resolved = assertInstanceOf(BlackjackService.SoloDealOutcome.Resolved::class.java, outcome)
        // Stake debited then 2.5x credited back: -100 +250 → +150 net.
        assertEquals(1_150L, user.socialCredit)
        assertEquals(1_150L, resolved.newBalance)
        assertEquals(Blackjack.Result.PLAYER_BLACKJACK, resolved.result.seatResults[discordId])
    }

    @Test
    fun `dealSolo with player and dealer blackjack pushes`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.ACE), c(Rank.KING)),
            mutableListOf(c(Rank.ACE, Suit.HEARTS), c(Rank.QUEEN, Suit.HEARTS))
        )
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PUSH

        val outcome = service.dealSolo(discordId, guildId, stake = 100L)
        val resolved = assertInstanceOf(BlackjackService.SoloDealOutcome.Resolved::class.java, outcome)
        // Push: -100 then +100 = 0 net.
        assertEquals(1_000L, user.socialCredit)
        assertEquals(1_000L, resolved.newBalance)
    }

    @Test
    fun `applySoloAction HIT continues when not bust`() {
        primeNonBjDeal(player = mutableListOf(c(Rank.SEVEN), c(Rank.FIVE)), dealer = mutableListOf(c(Rank.KING), c(Rank.FOUR)))
        val deal = service.dealSolo(discordId, guildId, stake = 50L)
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId
        // HIT adds a 3 → 15, still alive.
        every { blackjack.hit(any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.THREE))
        }
        val outcome = service.applySoloAction(discordId, guildId, tableId, Blackjack.Action.HIT)
        assertInstanceOf(BlackjackService.SoloActionOutcome.Continued::class.java, outcome)
    }

    @Test
    fun `applySoloAction HIT busts and resolves as PLAYER_BUST`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.KING), c(Rank.QUEEN)), // 20
            mutableListOf(c(Rank.NINE), c(Rank.NINE, Suit.HEARTS))
        )
        // No BJ — neither side has 21 in 2 cards.
        val deal = service.dealSolo(discordId, guildId, stake = 50L)
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId
        // user balance is now 950 (debited).
        // HIT adds a J → 30 → bust.
        every { blackjack.hit(any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.JACK, Suit.HEARTS))
        }
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_BUST
        val outcome = service.applySoloAction(discordId, guildId, tableId, Blackjack.Action.HIT)
        val resolved = assertInstanceOf(BlackjackService.SoloActionOutcome.Resolved::class.java, outcome)
        // Bust: no payout. Balance stays at 950.
        assertEquals(950L, user.socialCredit)
        assertEquals(Blackjack.Result.PLAYER_BUST, resolved.result.seatResults[discordId])
    }

    @Test
    fun `applySoloAction STAND triggers dealer play and credits win`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.KING), c(Rank.NINE)), // 19
            mutableListOf(c(Rank.NINE, Suit.HEARTS), c(Rank.SEVEN)) // 16
        )
        val deal = service.dealSolo(discordId, guildId, stake = 100L) // -100 → 900
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId
        // Dealer stands at 17 after one hit → no card change needed for assertion.
        every { blackjack.playOutDealer(any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.ACE, Suit.HEARTS)) // 16+11=soft 27 → uh, 16+1=17
        }
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_WIN
        val outcome = service.applySoloAction(discordId, guildId, tableId, Blackjack.Action.STAND)
        val resolved = assertInstanceOf(BlackjackService.SoloActionOutcome.Resolved::class.java, outcome)
        // Win 2x: -100 then +200 = +100 net → balance 1100.
        assertEquals(1_100L, user.socialCredit)
        assertEquals(1_100L, resolved.newBalance)
    }

    @Test
    fun `applySoloAction DOUBLE doubles debit and draws one card`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.FIVE), c(Rank.SIX)), // 11 — classic double
            mutableListOf(c(Rank.NINE), c(Rank.SEVEN)) // 16
        )
        val deal = service.dealSolo(discordId, guildId, stake = 100L) // 900
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId
        // DOUBLE will debit another 100 → 800, draw one (a 10 → 21), then play out dealer.
        every { blackjack.hit(any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.TEN, Suit.HEARTS))
        }
        every { blackjack.playOutDealer(any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.ACE, Suit.HEARTS))
        }
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_WIN

        val outcome = service.applySoloAction(discordId, guildId, tableId, Blackjack.Action.DOUBLE)
        val resolved = assertInstanceOf(BlackjackService.SoloActionOutcome.Resolved::class.java, outcome)
        // After deal: 900. After double debit: 800. Win 2× on doubled stake (200) → +400 → 1200.
        assertEquals(1_200L, user.socialCredit)
        assertEquals(1_200L, resolved.newBalance)
    }

    @Test
    fun `applySoloAction STAND credits player when dealer plays out and busts`() {
        // Regression for the freeze where STAND-then-dealer-bust paid no
        // winnings. Real evaluate() so the dealer-bust → PLAYER_WIN branch
        // is actually exercised (the win-on-stand test above stubs evaluate
        // and never sees the bust path).
        val realBj = Blackjack()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.KING), c(Rank.NINE)),                // 19
            mutableListOf(c(Rank.SIX), c(Rank.SEVEN, Suit.HEARTS))    // 13 — must hit
        )
        // Dealer at 13 must hit; drop a 10 → 23 → bust.
        every { blackjack.playOutDealer(any(), any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.TEN, Suit.HEARTS))
        }
        every { blackjack.evaluate(any(), any(), any()) } answers {
            realBj.evaluate(arg(0), arg(1), arg(2))
        }

        val deal = service.dealSolo(discordId, guildId, stake = 100L)  // -100 → 900
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId

        val outcome = service.applySoloAction(discordId, guildId, tableId, Blackjack.Action.STAND)
        val resolved = assertInstanceOf(BlackjackService.SoloActionOutcome.Resolved::class.java, outcome)

        assertEquals(1_100L, user.socialCredit, "win pays 2× stake net +100")
        assertEquals(1_100L, resolved.newBalance)
        assertEquals(Blackjack.Result.PLAYER_WIN, resolved.result.seatResults[discordId])
        assertEquals(200L, resolved.result.payouts[discordId])

        val live = registry.get(tableId)!!
        assertEquals(BlackjackTable.Phase.RESOLVED, live.phase)
        assertNotNull(live.lastResult, "lastResult must be set so /state can render the verdict")
        assertTrue(bestTotal(live.dealer) > 21, "sanity: dealer actually busted")
    }

    @Test
    fun `applySoloAction NotYourHand when other user clicks`() {
        primeNonBjDeal(player = mutableListOf(c(Rank.EIGHT), c(Rank.NINE)), dealer = mutableListOf(c(Rank.KING), c(Rank.FOUR)))
        val deal = service.dealSolo(discordId, guildId, stake = 50L)
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId
        val outcome = service.applySoloAction(otherDiscordId, guildId, tableId, Blackjack.Action.HIT)
        assertEquals(BlackjackService.SoloActionOutcome.NotYourHand, outcome)
    }

    @Test
    fun `applySoloAction HandNotFound when table id unknown`() {
        val outcome = service.applySoloAction(discordId, guildId, tableId = 9999L, Blackjack.Action.HIT)
        assertEquals(BlackjackService.SoloActionOutcome.HandNotFound, outcome)
    }

    @Test
    fun `closeSoloTable removes a resolved solo table`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.ACE), c(Rank.KING)),
            mutableListOf(c(Rank.NINE), c(Rank.SEVEN))
        )
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_BLACKJACK
        val outcome = service.dealSolo(discordId, guildId, stake = 50L)
        val tableId = (outcome as BlackjackService.SoloDealOutcome.Resolved).tableId
        service.closeSoloTable(tableId)
        assertNull(registry.get(tableId))
    }

    private fun primeNonBjDeal(player: MutableList<Card>, dealer: MutableList<Card>) {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(player, dealer)
    }

    // -------------------------------------------------------------------------
    // MULTI
    // -------------------------------------------------------------------------

    @Test
    fun `createMultiTable seats host and debits ante`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        val outcome = service.createMultiTable(discordId, guildId, ante = 100L)
        val ok = assertInstanceOf(BlackjackService.MultiCreateOutcome.Ok::class.java, outcome)
        val table = registry.get(ok.tableId)!!
        assertEquals(900L, user.socialCredit)
        assertEquals(1, table.seats.size)
        assertEquals(discordId, table.seats[0].discordId)
        assertEquals(BlackjackTable.Mode.MULTI, table.mode)
    }

    @Test
    fun `createMultiTable InvalidAnte when out of range`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns userWithBalance(1_000L)
        val outcome = service.createMultiTable(discordId, guildId, ante = 1L)
        assertInstanceOf(BlackjackService.MultiCreateOutcome.InvalidAnte::class.java, outcome)
    }

    @Test
    fun `createMultiTable InsufficientCredits when balance short`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns userWithBalance(5L)
        val outcome = service.createMultiTable(discordId, guildId, ante = 50L)
        assertInstanceOf(BlackjackService.MultiCreateOutcome.InsufficientCredits::class.java, outcome)
    }

    @Test
    fun `joinMultiTable seats a second player and debits their ante`() {
        val host = userWithBalance(1_000L, id = discordId)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        val join = service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        val ok = assertInstanceOf(BlackjackService.MultiJoinOutcome.Ok::class.java, join)
        assertEquals(1, ok.seatIndex)
        assertEquals(900L, joiner.socialCredit)
        val table = registry.get(create.tableId)!!
        assertEquals(2, table.seats.size)
    }

    @Test
    fun `joinMultiTable AlreadySeated when player joins twice`() {
        val host = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        val outcome = service.joinMultiTable(discordId, guildId, create.tableId)
        assertEquals(BlackjackService.MultiJoinOutcome.AlreadySeated, outcome)
    }

    @Test
    fun `leaveMultiTable refunds escrow when between hands`() {
        val host = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserById(discordId, guildId) } returns host
        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        // After create: balance 900.
        val outcome = service.leaveMultiTable(discordId, guildId, create.tableId)
        val ok = assertInstanceOf(BlackjackService.MultiLeaveOutcome.Ok::class.java, outcome)
        assertEquals(100L, ok.refund)
        assertEquals(1_000L, host.socialCredit, "ante refunded")
        assertNull(registry.get(create.tableId), "empty table dropped")
    }

    @Test
    fun `startMultiHand requires at least the minimum seat count`() {
        val host = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        val outcome = service.startMultiHand(discordId, guildId, create.tableId)
        assertEquals(BlackjackService.MultiStartOutcome.NotEnoughPlayers, outcome)
    }

    @Test
    fun `startMultiHand NotHost when non-host tries to deal`() {
        val host = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        val outcome = service.startMultiHand(otherDiscordId, guildId, create.tableId)
        assertEquals(BlackjackService.MultiStartOutcome.NotHost, outcome)
    }

    @Test
    fun `multi hand with all winners refunds stakes only — no losers means no bonus`() {
        // Two-seat table where both players win against the dealer. With
        // no losers contributing to the pot, each winner just gets their
        // stake back; nothing rakes to the jackpot.
        val host = userWithBalance(1_000L)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_WIN

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        assertEquals(900L, host.socialCredit)
        assertEquals(900L, joiner.socialCredit)

        service.startMultiHand(discordId, guildId, create.tableId) as BlackjackService.MultiStartOutcome.Ok
        service.applyMultiAction(discordId, guildId, create.tableId, Blackjack.Action.STAND)
        val resolved = service.applyMultiAction(otherDiscordId, guildId, create.tableId, Blackjack.Action.STAND)
            as BlackjackService.MultiActionOutcome.HandResolved

        // Both at the table win → losers' pool is 0 → each winner just
        // gets their 100 stake refunded. No rake (no pool to skim).
        assertEquals(1_000L, host.socialCredit)
        assertEquals(1_000L, joiner.socialCredit)
        assertEquals(0L, resolved.result.rake)
        verify(exactly = 0) { jackpotService.addToPool(guildId, any()) }
    }

    @Test
    fun `multi hand pays winner from loser's stake with 5pct rake`() {
        val host = userWithBalance(1_000L)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner
        // Host wins, joiner busts.
        every { blackjack.evaluate(any(), any()) } answers {
            // Order isn't deterministic per call — pin off who's playing.
            // We rely on the order winners are listed via seat order.
            Blackjack.Result.PLAYER_WIN
        }
        // Stub per-seat by capturing the player hand reference.
        // Simpler: just stub differently via answers cycle.
        var n = 0
        every { blackjack.evaluate(any(), any()) } answers {
            n++
            if (n == 1) Blackjack.Result.PLAYER_WIN else Blackjack.Result.DEALER_WIN
        }

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        service.startMultiHand(discordId, guildId, create.tableId)
        service.applyMultiAction(discordId, guildId, create.tableId, Blackjack.Action.STAND)
        val resolved = service.applyMultiAction(otherDiscordId, guildId, create.tableId, Blackjack.Action.STAND)
            as BlackjackService.MultiActionOutcome.HandResolved

        // Pot=200, losersPool=100, baseRake=5, payable=95, host entitled to 100.
        // 95 < 100 → scaled: host gets 95. Plus stake refund 100 → +95 net.
        // Host: 900 + 100 (refund) + 95 (share) = 1095. Joiner: 900 (lost stake).
        assertEquals(1_095L, host.socialCredit)
        assertEquals(900L, joiner.socialCredit)
        assertEquals(5L, resolved.result.rake)
        verify { jackpotService.addToPool(guildId, 5L) }
    }

    @Test
    fun `multi hand BJ winner takes 1_5x premium plus stake refund from losers pool`() {
        // 3-seat table so the losers' pool (200) can cover the host's
        // BJ premium (150) with surplus to spare. Everyone stands so the
        // resolution path is deterministic regardless of which cards the
        // real Deck dealt — outcomes are pinned by the evaluate stub.
        val host = userWithBalance(1_000L)
        val a = userWithBalance(1_000L, id = otherDiscordId)
        val b = userWithBalance(1_000L, id = 102L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns a
        every { userService.getUserByIdForUpdate(102L, guildId) } returns b
        every { blackjack.evaluate(any(), any()) } returnsMany listOf(
            Blackjack.Result.PLAYER_BLACKJACK,
            Blackjack.Result.DEALER_WIN,
            Blackjack.Result.DEALER_WIN
        )

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        service.joinMultiTable(102L, guildId, create.tableId)
        service.startMultiHand(discordId, guildId, create.tableId)
        service.applyMultiAction(discordId, guildId, create.tableId, Blackjack.Action.STAND)
        service.applyMultiAction(otherDiscordId, guildId, create.tableId, Blackjack.Action.STAND)
        val resolved = service.applyMultiAction(102L, guildId, create.tableId, Blackjack.Action.STAND)
            as BlackjackService.MultiActionOutcome.HandResolved

        // Pot=300, losersPool=200, baseRake=10, payable=190.
        // BJ entitled = 100*1.5 = 150. Total entitled 150 ≤ 190 → full
        // bonus. Surplus 40 → jackpot. Total rake = 10 + 40 = 50.
        // Host payout: 100 (refund) + 150 (premium) = 250 → 900+250 = 1150.
        assertEquals(1_150L, host.socialCredit)
        assertEquals(900L, a.socialCredit)
        assertEquals(900L, b.socialCredit)
        assertEquals(50L, resolved.result.rake)
        verify { jackpotService.addToPool(guildId, 50L) }
    }

    @Test
    fun `multi hand routes entire pot to jackpot when all seats bust`() {
        val host = userWithBalance(1_000L)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_BUST
        every { blackjack.hit(any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.KING, Suit.HEARTS))
        }

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        service.startMultiHand(discordId, guildId, create.tableId)

        // Both HIT → bust on each turn.
        service.applyMultiAction(discordId, guildId, create.tableId, Blackjack.Action.HIT)
        val outcome = service.applyMultiAction(otherDiscordId, guildId, create.tableId, Blackjack.Action.HIT)
        val resolved = assertInstanceOf(BlackjackService.MultiActionOutcome.HandResolved::class.java, outcome)

        // Both bust: no winners, no pushes — entire 200 to jackpot.
        verify { jackpotService.addToPool(guildId, 200L) }
        assertEquals(900L, host.socialCredit, "host loses ante")
        assertEquals(900L, joiner.socialCredit, "joiner loses ante")
        assertEquals(0, resolved.result.payouts.values.sum())
    }

    @Test
    fun `applyMultiAction NotYourTurn when wrong actor clicks`() {
        val host = userWithBalance(1_000L)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        service.startMultiHand(discordId, guildId, create.tableId)
        // First actor is host; joiner clicks out of turn.
        val outcome = service.applyMultiAction(otherDiscordId, guildId, create.tableId, Blackjack.Action.HIT)
        assertEquals(BlackjackService.MultiActionOutcome.NotYourTurn, outcome)
    }

    @Test
    fun `evictAllSeats refunds every seat's escrowed stake`() {
        val host = userWithBalance(1_000L)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner
        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        // Both at 900.
        val table = registry.get(create.tableId)!!
        service.evictAllSeats(table)
        assertEquals(1_000L, host.socialCredit)
        assertEquals(1_000L, joiner.socialCredit)
        assertTrue(table.seats.isEmpty())
    }

    @Test
    fun `loss path deposits jackpot tribute via JackpotHelper`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // No JACKPOT_LOSS_TRIBUTE_PCT config → defaults to 10%.
        every { configService.getConfigByName(any(), any()) } returns null
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.KING), c(Rank.QUEEN)), // 20
            mutableListOf(c(Rank.NINE), c(Rank.NINE, Suit.HEARTS))
        )
        val deal = service.dealSolo(discordId, guildId, stake = 100L)
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId
        every { blackjack.hit(any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.JACK, Suit.HEARTS))
        }
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_BUST

        val outcome = service.applySoloAction(discordId, guildId, tableId, Blackjack.Action.HIT)
        val resolved = assertInstanceOf(BlackjackService.SoloActionOutcome.Resolved::class.java, outcome)
        // 10% of 100 = 10 to jackpot pool.
        assertEquals(10L, resolved.lossTribute)
        verify { jackpotService.addToPool(guildId, 10L) }
    }

    // -------------------------------------------------------------------------
    // FOLLOW-UP BEHAVIOUR
    // -------------------------------------------------------------------------

    @Test
    fun `multi seats persist across hands and re-ante on the next start`() {
        val host = userWithBalance(1_000L)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_WIN

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        // First hand: starts and resolves with both winning (no-loser refund path).
        service.startMultiHand(discordId, guildId, create.tableId)
        service.applyMultiAction(discordId, guildId, create.tableId, Blackjack.Action.STAND)
        service.applyMultiAction(otherDiscordId, guildId, create.tableId, Blackjack.Action.STAND)

        // Table should still exist with both seats kept around for next hand.
        val tableAfter = registry.get(create.tableId)
        assertNotNull(tableAfter, "table kept between hands")
        assertEquals(2, tableAfter!!.seats.size)
        assertEquals(0L, tableAfter.seats[0].stake, "stake reset between hands")
        assertEquals(0L, tableAfter.seats[1].stake)
        assertEquals(1_000L, host.socialCredit)
        assertEquals(1_000L, joiner.socialCredit)

        // Second hand: re-debit each seat's ante.
        service.startMultiHand(discordId, guildId, create.tableId) as BlackjackService.MultiStartOutcome.Ok
        assertEquals(900L, host.socialCredit, "ante re-debited")
        assertEquals(900L, joiner.socialCredit)
    }

    @Test
    fun `mid-hand leave queues for end of hand and auto-stands the actor`() {
        val host = userWithBalance(1_000L)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PUSH

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        service.startMultiHand(discordId, guildId, create.tableId)

        // Host (current actor) issues /blackjack leave mid-hand.
        val outcome = service.leaveMultiTable(discordId, guildId, create.tableId)
        val queued = assertInstanceOf(BlackjackService.MultiLeaveOutcome.QueuedForEndOfHand::class.java, outcome)
        assertEquals(100L, queued.stakeHeld)
        // Auto-stand drove actor advance — actor should now be the joiner.
        val live = registry.get(create.tableId)!!
        assertEquals(otherDiscordId, live.seats[live.actorIndex].discordId)

        // Finish the hand: joiner stands → both push → both refunded.
        service.applyMultiAction(otherDiscordId, guildId, create.tableId, Blackjack.Action.STAND)

        // Host (leaving) refunded their pushed ante; seat is dropped.
        assertEquals(1_000L, host.socialCredit)
        val final = registry.get(create.tableId)
        assertNotNull(final)
        assertEquals(1, final!!.seats.size, "leaving seat dropped post-hand")
        assertEquals(otherDiscordId, final.seats[0].discordId)
    }

    @Test
    fun `mid-hand leave second click is idempotent`() {
        val host = userWithBalance(1_000L)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        service.startMultiHand(discordId, guildId, create.tableId)

        service.leaveMultiTable(discordId, guildId, create.tableId)
        val second = service.leaveMultiTable(discordId, guildId, create.tableId)
        assertEquals(BlackjackService.MultiLeaveOutcome.AlreadyLeaving, second)
    }

    @Test
    fun `applyMultiAction cascades past pendingLeave seats`() {
        val host = userWithBalance(1_000L)
        val a = userWithBalance(1_000L, id = otherDiscordId)
        val b = userWithBalance(1_000L, id = 102L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns a
        every { userService.getUserByIdForUpdate(102L, guildId) } returns b
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_WIN

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        service.joinMultiTable(102L, guildId, create.tableId)
        service.startMultiHand(discordId, guildId, create.tableId)

        // Middle seat asks to leave (not the current actor).
        val mid = service.leaveMultiTable(otherDiscordId, guildId, create.tableId)
        assertInstanceOf(BlackjackService.MultiLeaveOutcome.QueuedForEndOfHand::class.java, mid)

        // Host stands. Cascade should auto-stand the leaving seat and
        // advance to seat #2 (id 102) without seat #1 (otherDiscordId)
        // ever taking its turn.
        service.applyMultiAction(discordId, guildId, create.tableId, Blackjack.Action.STAND)
        val live = registry.get(create.tableId)!!
        assertEquals(102L, live.seats[live.actorIndex].discordId)
    }

    @Test
    fun `multi tables get a shot clock deadline armed on start`() {
        // Clock is on by default for multi tables.
        val host = userWithBalance(1_000L)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        service.startMultiHand(discordId, guildId, create.tableId)

        val table = registry.get(create.tableId)!!
        assertNotNull(table.currentActorDeadline, "shot clock armed on start")
        assertEquals(Blackjack.MULTI_SHOT_CLOCK_SECONDS, table.shotClockSeconds)
    }

    @Test
    fun `startMultiHand drops seats that can't afford the next hand`() {
        val host = userWithBalance(1_000L)
        val poor = userWithBalance(150L, id = otherDiscordId)  // can pay 1st but not 2nd
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns poor
        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_BUST
        every { blackjack.hit(any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.KING, Suit.HEARTS))
        }

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        // Both at: host 900, poor 50.

        // First hand both bust → losers' pool to jackpot, balances unchanged.
        service.startMultiHand(discordId, guildId, create.tableId)
        service.applyMultiAction(discordId, guildId, create.tableId, Blackjack.Action.HIT)
        service.applyMultiAction(otherDiscordId, guildId, create.tableId, Blackjack.Action.HIT)

        // Try a second hand — `poor` has 50 credits, can't cover the 100 ante.
        // Single seat can pay → falls below MIN_SEATS → NotEnoughPlayers,
        // unfunded seat dropped from table.
        val outcome = service.startMultiHand(discordId, guildId, create.tableId)
        assertEquals(BlackjackService.MultiStartOutcome.NotEnoughPlayers, outcome)
        val live = registry.get(create.tableId)!!
        assertEquals(1, live.seats.size)
        assertEquals(discordId, live.seats[0].discordId, "host kept; poor dropped")
        assertEquals(900L, host.socialCredit, "host not debited when start aborted")
    }

    // -------------------------------------------------------------------------
    // SPLIT
    // -------------------------------------------------------------------------

    @Test
    fun `solo SPLIT debits a second ante and creates two hands`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        // 8-8 → splittable pair. Dealer shows a 6 (will play out later).
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.EIGHT), c(Rank.EIGHT, Suit.HEARTS)),
            mutableListOf(c(Rank.SIX), c(Rank.TEN))
        )
        val deal = service.dealSolo(discordId, guildId, stake = 100L)
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId
        assertEquals(900L, user.socialCredit, "first ante debited at deal")

        // SPLIT — service deals one card to each split hand. Stub hit
        // to drop a non-bust card on each so we end up with two
        // post-split hands the player can still hit/stand on.
        every { blackjack.hit(any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.THREE, Suit.HEARTS))
        }
        // dealStartingHands returned a fresh deck via newDeck() (relaxed
        // mock). The split path doesn't go through newDeck(); it deals
        // off the table's stored deck instance.

        val outcome = service.applySoloAction(discordId, guildId, tableId, Blackjack.Action.SPLIT)
        assertInstanceOf(BlackjackService.SoloActionOutcome.Continued::class.java, outcome)
        assertEquals(800L, user.socialCredit, "second ante debited on SPLIT")
        val live = registry.get(tableId)!!
        val seat = live.seats[0]
        assertEquals(2, seat.hands.size, "split produced two hand-slots")
        assertEquals(0, seat.activeHandIndex, "first split hand active first")
        assertTrue(seat.hands.all { it.fromSplit }, "both slots flagged fromSplit")
    }

    @Test
    fun `solo SPLIT rejected when hand is not a pair`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.EIGHT), c(Rank.NINE)),
            mutableListOf(c(Rank.SIX), c(Rank.TEN))
        )
        val deal = service.dealSolo(discordId, guildId, stake = 100L)
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId

        val outcome = service.applySoloAction(discordId, guildId, tableId, Blackjack.Action.SPLIT)
        assertEquals(BlackjackService.SoloActionOutcome.IllegalAction, outcome)
        assertEquals(900L, user.socialCredit, "no second-ante debit on rejected SPLIT")
    }

    @Test
    fun `solo SPLIT rejected without funds for the second ante`() {
        val user = userWithBalance(150L) // can pay 100 ante but not another 100
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.EIGHT), c(Rank.EIGHT, Suit.HEARTS)),
            mutableListOf(c(Rank.SIX), c(Rank.TEN))
        )
        val deal = service.dealSolo(discordId, guildId, stake = 100L)
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId
        // After deal: balance is 50.
        val outcome = service.applySoloAction(discordId, guildId, tableId, Blackjack.Action.SPLIT)
        val short = assertInstanceOf(
            BlackjackService.SoloActionOutcome.InsufficientCreditsForSplit::class.java, outcome
        )
        assertEquals(100L, short.needed)
        assertEquals(50L, short.have)
    }

    @Test
    fun `solo SPLIT aces auto-stand both hands and play out the dealer`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { blackjack.dealStartingHands(any()) } returns Blackjack.StartingDeal(
            mutableListOf(c(Rank.ACE), c(Rank.ACE, Suit.HEARTS)),
            mutableListOf(c(Rank.NINE), c(Rank.SEVEN))
        )
        val deal = service.dealSolo(discordId, guildId, stake = 100L)
        val tableId = (deal as BlackjackService.SoloDealOutcome.Dealt).tableId

        // Each split hand gets one card dealt (which we stub).
        every { blackjack.hit(any(), any()) } answers {
            firstArg<MutableList<Card>>().add(c(Rank.KING, Suit.HEARTS))
        }
        // Both split-hand 21s won't claim natural BJ premium; treat as regular wins.
        every { blackjack.evaluate(any(), any(), any()) } returns Blackjack.Result.PLAYER_WIN

        val outcome = service.applySoloAction(discordId, guildId, tableId, Blackjack.Action.SPLIT)
        // Aces split auto-stands both hands → resolution happens immediately.
        val resolved = assertInstanceOf(BlackjackService.SoloActionOutcome.Resolved::class.java, outcome)
        // Both hands win 1× → balance: 800 (after split) + 100 stake refund * 2 + 100 win * 2 = 1200.
        assertEquals(1_200L, user.socialCredit)
        // Two PerHandResult entries.
        assertEquals(2, resolved.result.perHandResults.size)
        assertTrue(resolved.result.perHandResults.all { it.fromSplit })
    }

    @Test
    fun `slot capture is harmless`() {
        val s = slot<UserDto>()
        // sanity to ensure mockk imports stay (test won't compile without them used)
        every { userService.updateUser(capture(s)) } answers { firstArg() }
        userService.updateUser(UserDto(1L, 1L))
        assertEquals(1L, s.captured.discordId)
    }
}
