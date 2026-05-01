package database.service

import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
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
        every { blackjack.multiplier(any()) } answers { realMultiplier(arg(0)) }
        service = BlackjackService(userService, jackpotService, configService, registry, blackjack, Random(0))
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
    fun `multi hand resolves with split pot on player wins and rake to jackpot`() {
        // Two-seat table: both players win.
        val host = userWithBalance(1_000L)
        val joiner = userWithBalance(1_000L, id = otherDiscordId)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns host
        every { userService.getUserByIdForUpdate(otherDiscordId, guildId) } returns joiner
        // Note: lockUsersInAscendingOrder is a top-level extension that
        // dispatches to getUserByIdForUpdate per id — the mocks above
        // already cover the per-id calls that flow through it.

        every { blackjack.evaluate(any(), any()) } returns Blackjack.Result.PLAYER_WIN

        val create = service.createMultiTable(discordId, guildId, ante = 100L)
            as BlackjackService.MultiCreateOutcome.Ok
        service.joinMultiTable(otherDiscordId, guildId, create.tableId)
        // Each create/join debited 100; both at 900.
        assertEquals(900L, host.socialCredit)
        assertEquals(900L, joiner.socialCredit)

        service.startMultiHand(discordId, guildId, create.tableId) as BlackjackService.MultiStartOutcome.Ok
        // Each player: STAND in turn.
        val first = service.applyMultiAction(discordId, guildId, create.tableId, Blackjack.Action.STAND)
        assertInstanceOf(BlackjackService.MultiActionOutcome.Continued::class.java, first)
        val second = service.applyMultiAction(otherDiscordId, guildId, create.tableId, Blackjack.Action.STAND)
        val resolved = assertInstanceOf(BlackjackService.MultiActionOutcome.HandResolved::class.java, second)

        // Pot = 200. Both win → 5% rake = 10 → jackpot. Remaining 190
        // splits → 95 each. Host had 900 → 995. Joiner 900 → 995.
        verify { jackpotService.addToPool(guildId, 10L) }
        assertEquals(995L, host.socialCredit)
        assertEquals(995L, joiner.socialCredit)
        assertEquals(2, resolved.result.seatResults.size)
        assertEquals(200L, resolved.result.pot)
        assertEquals(10L, resolved.result.rake)
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

    @Test
    fun `slot capture is harmless`() {
        val s = slot<UserDto>()
        // sanity to ensure mockk imports stay (test won't compile without them used)
        every { userService.updateUser(capture(s)) } answers { firstArg() }
        userService.updateUser(UserDto(1L, 1L))
        assertEquals(1L, s.captured.discordId)
    }
}
