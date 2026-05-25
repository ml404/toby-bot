package database.service

import common.card.Card
import common.card.Rank
import common.card.Suit
import database.dto.user.UserDto
import common.casino.casinoholdem.CasinoHoldem
import common.casino.casinoholdem.CasinoHoldemTable
import database.poker.CasinoHoldemTableRegistry
import common.casino.poker.HandEvaluator
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
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.random.Random
import database.service.casino.casinoholdem.CasinoHoldemService
import database.service.guild.ConfigService
import database.service.economy.JackpotService
import database.service.user.UserService
import common.events.casino.casinoholdem.CasinoHoldemWonEvent

class CasinoHoldemServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService
    private lateinit var game: CasinoHoldem
    private lateinit var registry: CasinoHoldemTableRegistry
    private lateinit var service: CasinoHoldemService

    private val discordId = 100L
    private val guildId = 200L

    private fun userWithBalance(balance: Long): UserDto =
        UserDto(discordId, guildId).apply { socialCredit = balance }

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        game = mockk(relaxed = true)
        // Delegate the (deterministic) per-leg multiplier lookups to a
        // real CasinoHoldem so we don't have to spell out the paytable
        // in every test. dealAll / resolve stay stubbed per-test.
        val real = CasinoHoldem()
        every { game.anteMultiplier(any()) } answers {
            real.anteMultiplier(it.invocation.args[0] as CasinoHoldem.AnteResult)
        }
        every { game.callMultiplier(any()) } answers {
            real.callMultiplier(it.invocation.args[0] as CasinoHoldem.CallResult)
        }
        // Real registry — its scheduler is harmless and we drive idle
        // sweeps directly from the test.
        registry = CasinoHoldemTableRegistry(
            idleTtl = Duration.ofMinutes(10),
            sweepInterval = Duration.ofHours(1),
            scheduler = Executors.newScheduledThreadPool(1),
        )
        service = CasinoHoldemService(
            userService = userService,
            jackpotService = jackpotService,
            configService = configService,
            tableRegistry = registry,
            game = game,
            tradeService = null,
            marketService = null,
            random = Random(0),
        )
        // PostConstruct — wires the idle-evict callback.
        service.wireRegistry()
    }

    private fun seededDeal(): CasinoHoldem.Deal = CasinoHoldem.Deal(
        playerHole = listOf(Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.SPADES)),
        dealerHole = listOf(Card(Rank.TWO, Suit.HEARTS), Card(Rank.THREE, Suit.DIAMONDS)),
        flop = listOf(
            Card(Rank.QUEEN, Suit.SPADES),
            Card(Rank.JACK, Suit.SPADES),
            Card(Rank.TEN, Suit.SPADES),
        ),
        turn = Card(Rank.NINE, Suit.CLUBS),
        river = Card(Rank.EIGHT, Suit.DIAMONDS),
    )

    private fun stubGameDealAll() {
        every { game.newDeck() } returns mockk(relaxed = true)
        every { game.dealAll(any()) } returns seededDeal()
    }

    // ---------- dealSolo ----------

    @Test
    fun `dealSolo InvalidStake when stake out of bounds`() {
        val outcome = service.dealSolo(discordId, guildId, stake = CasinoHoldem.MIN_STAKE - 1)

        val invalid = assertInstanceOf(CasinoHoldemService.DealOutcome.InvalidStake::class.java, outcome)
        assertEquals(CasinoHoldem.MIN_STAKE, invalid.min)
        assertEquals(CasinoHoldem.MAX_STAKE, invalid.max)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
    }

    @Test
    fun `dealSolo UnknownUser when no row in DB`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.dealSolo(discordId, guildId, stake = 50L)

        assertEquals(CasinoHoldemService.DealOutcome.UnknownUser, outcome)
    }

    @Test
    fun `dealSolo InsufficientCredits when balance below stake`() {
        val user = userWithBalance(20L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.dealSolo(discordId, guildId, stake = 50L)

        val short = assertInstanceOf(
            CasinoHoldemService.DealOutcome.InsufficientCredits::class.java, outcome
        )
        assertEquals(50L, short.stake)
        assertEquals(20L, short.have)
        verify(exactly = 0) { userService.updateUser(any()) }
    }

    @Test
    fun `dealSolo happy path debits stake, registers a table in AWAIT_DECISION`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.dealSolo(discordId, guildId, stake = 100L)

        val dealt = assertInstanceOf(CasinoHoldemService.DealOutcome.Dealt::class.java, outcome)
        assertEquals(900L, dealt.newBalance)
        assertEquals(900L, captured.captured.socialCredit)

        val table = registry.get(dealt.tableId)
        assertTrue(table != null)
        table!!
        assertEquals(CasinoHoldemTable.Phase.AWAIT_DECISION, table.phase)
        assertEquals(2, table.playerHole.size)
        assertEquals(2, table.dealerHole.size)
        assertEquals(3, table.board.size)
        assertEquals(Card(Rank.NINE, Suit.CLUBS), table.pendingTurn)
        assertEquals(Card(Rank.EIGHT, Suit.DIAMONDS), table.pendingRiver)
        assertEquals(100L, table.stake)
    }

    // ---------- applyAction(FOLD) ----------

    @Test
    fun `applyAction FOLD resolves the table, no payout, ante loss-tributed to jackpot`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user
        // 10% of 100 stake = 10 tribute (default DEFAULT_LOSS_TRIBUTE is 10%).
        every { configService.getConfigByName(any(), any()) } returns null

        val dealt = service.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt

        val outcome = service.applyAction(
            discordId, guildId, dealt.tableId, CasinoHoldem.Action.FOLD
        )

        val resolved = assertInstanceOf(CasinoHoldemService.ActionOutcome.Resolved::class.java, outcome)
        assertTrue(resolved.result.folded)
        assertNull(resolved.result.resolution)
        assertEquals(0L, resolved.result.totalPayout)
        assertEquals(100L, resolved.result.anteStake)
        assertEquals(0L, resolved.result.callStake)
        assertEquals(0L, resolved.jackpotPayout)
        assertEquals(10L, resolved.lossTribute)

        val table = registry.get(dealt.tableId)!!
        assertEquals(CasinoHoldemTable.Phase.RESOLVED, table.phase)
        verify { jackpotService.addToPool(guildId, 10L) }
    }

    // ---------- applyAction(CALL) ----------

    @Test
    fun `applyAction CALL with insufficient credits returns InsufficientCreditsForCall`() {
        // Wallet has just enough for the deal but not for the call (2 × stake).
        val user = userWithBalance(120L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user

        val dealt = service.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt
        // After dealing, balance = 20, callStake = 200 — short.

        val outcome = service.applyAction(
            discordId, guildId, dealt.tableId, CasinoHoldem.Action.CALL
        )

        val short = assertInstanceOf(
            CasinoHoldemService.ActionOutcome.InsufficientCreditsForCall::class.java, outcome
        )
        assertEquals(200L, short.needed)
        assertEquals(20L, short.have)
        // Table remains in AWAIT_DECISION so the player can fold instead.
        assertEquals(CasinoHoldemTable.Phase.AWAIT_DECISION, registry.get(dealt.tableId)!!.phase)
    }

    @Test
    fun `applyAction CALL pays ante even-money and call 2x on a flush win`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user
        every { configService.getConfigByName(any(), any()) } returns null

        // Drive resolve() to a known WIN_FLUSH outcome.
        val playerRank = HandEvaluator.HandRank(
            HandEvaluator.Category.FLUSH, listOf(14, 13, 11, 5, 3)
        )
        val dealerRank = HandEvaluator.HandRank(
            HandEvaluator.Category.PAIR, listOf(13, 12, 11, 9)
        )
        every { game.resolve(any(), any(), any()) } returns CasinoHoldem.Resolution(
            playerRank = playerRank,
            dealerRank = dealerRank,
            dealerQualified = true,
            anteResult = CasinoHoldem.AnteResult.WIN,
            callResult = CasinoHoldem.CallResult.WIN_FLUSH,
        )

        val dealt = service.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt

        val outcome = service.applyAction(
            discordId, guildId, dealt.tableId, CasinoHoldem.Action.CALL
        )

        val resolved = assertInstanceOf(CasinoHoldemService.ActionOutcome.Resolved::class.java, outcome)
        // Wallet timeline: start 1000 → -100 deal → -200 call → +200 ante (100*2.0) +600 call (200*3.0)
        // = 700 → 500 → 700 → 1300
        assertEquals(200L, resolved.result.antePayout)
        assertEquals(600L, resolved.result.callPayout)
        assertEquals(800L, resolved.result.totalPayout)
        assertEquals(100L, resolved.result.anteStake)
        assertEquals(200L, resolved.result.callStake)
        // Two WIN legs roll independently against the jackpot — both miss
        // here at the default 1% rate with a deterministic Random(0). Just
        // assert the loss path didn't fire.
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
        assertEquals(CasinoHoldemTable.Phase.RESOLVED, registry.get(dealt.tableId)!!.phase)
    }

    @Test
    fun `applyAction CALL on dealer-no-qualify pushes the call leg`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user
        every { configService.getConfigByName(any(), any()) } returns null

        val anyRank = HandEvaluator.HandRank(HandEvaluator.Category.HIGH_CARD, listOf(14, 13, 11, 9, 5))
        every { game.resolve(any(), any(), any()) } returns CasinoHoldem.Resolution(
            playerRank = anyRank,
            dealerRank = anyRank,
            dealerQualified = false,
            anteResult = CasinoHoldem.AnteResult.WIN,
            callResult = CasinoHoldem.CallResult.PUSH,
        )

        val dealt = service.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt

        val outcome = service.applyAction(
            discordId, guildId, dealt.tableId, CasinoHoldem.Action.CALL
        )

        val resolved = assertInstanceOf(CasinoHoldemService.ActionOutcome.Resolved::class.java, outcome)
        // Ante WIN: 100 * 2.0 = 200; call PUSH: 200 * 1.0 = 200; total = 400.
        assertEquals(200L, resolved.result.antePayout)
        assertEquals(200L, resolved.result.callPayout)
        assertEquals(400L, resolved.result.totalPayout)
    }

    @Test
    fun `applyAction CALL loses both legs when dealer qualifies and out-ranks player`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user
        every { configService.getConfigByName(any(), any()) } returns null

        val anyRank = HandEvaluator.HandRank(HandEvaluator.Category.PAIR, listOf(5, 14, 12, 11))
        every { game.resolve(any(), any(), any()) } returns CasinoHoldem.Resolution(
            playerRank = anyRank,
            dealerRank = anyRank,
            dealerQualified = true,
            anteResult = CasinoHoldem.AnteResult.LOSE,
            callResult = CasinoHoldem.CallResult.LOSE,
        )

        val dealt = service.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt

        val outcome = service.applyAction(
            discordId, guildId, dealt.tableId, CasinoHoldem.Action.CALL
        )

        val resolved = assertInstanceOf(CasinoHoldemService.ActionOutcome.Resolved::class.java, outcome)
        assertEquals(0L, resolved.result.antePayout)
        assertEquals(0L, resolved.result.callPayout)
        assertEquals(0L, resolved.result.totalPayout)
        // Default 10% loss tribute on each leg's at-risk amount: 100 * .10 + 200 * .10 = 30.
        assertEquals(30L, resolved.lossTribute)
        verify { jackpotService.addToPool(guildId, 10L) }
        verify { jackpotService.addToPool(guildId, 20L) }
    }

    // ---------- HandNotFound / NotYourHand / IllegalAction ----------

    @Test
    fun `applyAction returns HandNotFound when table id is unknown`() {
        val outcome = service.applyAction(discordId, guildId, tableId = 999L, CasinoHoldem.Action.FOLD)
        assertEquals(CasinoHoldemService.ActionOutcome.HandNotFound, outcome)
    }

    @Test
    fun `applyAction returns NotYourHand when caller is not the table owner`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user

        val dealt = service.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt

        val outcome = service.applyAction(
            discordId = 999L, guildId, dealt.tableId, CasinoHoldem.Action.FOLD
        )
        assertEquals(CasinoHoldemService.ActionOutcome.NotYourHand, outcome)
    }

    @Test
    fun `applyAction on a RESOLVED table is rejected as IllegalAction`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user
        every { configService.getConfigByName(any(), any()) } returns null

        val dealt = service.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt
        service.applyAction(discordId, guildId, dealt.tableId, CasinoHoldem.Action.FOLD)

        val second = service.applyAction(
            discordId, guildId, dealt.tableId, CasinoHoldem.Action.FOLD
        )
        assertEquals(CasinoHoldemService.ActionOutcome.IllegalAction, second)
    }

    // ---------- closeSoloTable + idle eviction refund ----------

    @Test
    fun `closeSoloTable removes the table only when phase is RESOLVED`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user
        every { configService.getConfigByName(any(), any()) } returns null

        val dealt = service.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt
        // Mid-hand: closeSoloTable is a no-op.
        service.closeSoloTable(dealt.tableId)
        assertTrue(registry.get(dealt.tableId) != null)

        service.applyAction(discordId, guildId, dealt.tableId, CasinoHoldem.Action.FOLD)
        service.closeSoloTable(dealt.tableId)
        assertNull(registry.get(dealt.tableId))
    }

    @Test
    fun `idle eviction refunds the stake on tables stuck in AWAIT_DECISION`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user

        val dealt = service.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt
        // Force the table's lastActivityAt into the past so the sweep evicts it.
        val table = registry.get(dealt.tableId)!!
        table.lastActivityAt = Instant.now().minus(Duration.ofHours(1))

        registry.sweepIdle(Instant.now())

        // The eviction callback runs with whatever the user balance was
        // at the time of refund: it adds the stake back.
        verify { userService.updateUser(match { it.socialCredit == 1_000L }) }
        assertNull(registry.get(dealt.tableId))
    }

    // -------------------------------------------------------------------------
    // CasinoHoldemWonEvent (PR #520 follow-up)
    // -------------------------------------------------------------------------

    private fun serviceWithPublisher(): Pair<CasinoHoldemService, CasinoEventPublisherFake> {
        val publisher = CasinoEventPublisherFake()
        val withPublisher = CasinoHoldemService(
            userService = userService,
            jackpotService = jackpotService,
            configService = configService,
            tableRegistry = registry,
            game = game,
            tradeService = null,
            marketService = null,
            eventPublisher = publisher,
            random = Random(0),
        )
        withPublisher.wireRegistry()
        return withPublisher to publisher
    }

    @Test
    fun `ante WIN with call WIN publishes exactly one CasinoHoldemWonEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user
        every { configService.getConfigByName(any(), any()) } returns null

        val playerRank = HandEvaluator.HandRank(HandEvaluator.Category.FLUSH, listOf(14, 13, 11, 5, 3))
        val dealerRank = HandEvaluator.HandRank(HandEvaluator.Category.PAIR, listOf(13, 12, 11, 9))
        every { game.resolve(any(), any(), any()) } returns CasinoHoldem.Resolution(
            playerRank = playerRank,
            dealerRank = dealerRank,
            dealerQualified = true,
            anteResult = CasinoHoldem.AnteResult.WIN,
            callResult = CasinoHoldem.CallResult.WIN_FLUSH,
        )

        val dealt = svc.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt
        svc.applyAction(discordId, guildId, dealt.tableId, CasinoHoldem.Action.CALL)

        assertEquals(1, publisher.casinoHoldemWins.size)
        val event = publisher.casinoHoldemWins.single()
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
    }

    @Test
    fun `ante PUSH with call WIN still publishes a CasinoHoldemWonEvent (call leg is a win)`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user
        every { configService.getConfigByName(any(), any()) } returns null

        val anyRank = HandEvaluator.HandRank(HandEvaluator.Category.HIGH_CARD, listOf(14, 13, 11, 9, 5))
        every { game.resolve(any(), any(), any()) } returns CasinoHoldem.Resolution(
            playerRank = anyRank,
            dealerRank = anyRank,
            dealerQualified = true,
            anteResult = CasinoHoldem.AnteResult.PUSH,
            callResult = CasinoHoldem.CallResult.WIN_OTHER,
        )

        val dealt = svc.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt
        svc.applyAction(discordId, guildId, dealt.tableId, CasinoHoldem.Action.CALL)

        assertEquals(1, publisher.casinoHoldemWins.size)
    }

    @Test
    fun `both legs lose publishes no CasinoHoldemWonEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user
        every { configService.getConfigByName(any(), any()) } returns null

        val anyRank = HandEvaluator.HandRank(HandEvaluator.Category.PAIR, listOf(5, 14, 12, 11))
        every { game.resolve(any(), any(), any()) } returns CasinoHoldem.Resolution(
            playerRank = anyRank,
            dealerRank = anyRank,
            dealerQualified = true,
            anteResult = CasinoHoldem.AnteResult.LOSE,
            callResult = CasinoHoldem.CallResult.LOSE,
        )

        val dealt = svc.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt
        svc.applyAction(discordId, guildId, dealt.tableId, CasinoHoldem.Action.CALL)

        assertTrue(publisher.casinoHoldemWins.isEmpty())
    }

    @Test
    fun `pure push (ante PUSH and call PUSH) publishes no CasinoHoldemWonEvent`() {
        val (svc, publisher) = serviceWithPublisher()
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        stubGameDealAll()
        every { userService.updateUser(any()) } returns user
        every { configService.getConfigByName(any(), any()) } returns null

        val anyRank = HandEvaluator.HandRank(HandEvaluator.Category.HIGH_CARD, listOf(14, 13, 11, 9, 5))
        every { game.resolve(any(), any(), any()) } returns CasinoHoldem.Resolution(
            playerRank = anyRank,
            dealerRank = anyRank,
            dealerQualified = false,
            anteResult = CasinoHoldem.AnteResult.PUSH,
            callResult = CasinoHoldem.CallResult.PUSH,
        )

        val dealt = svc.dealSolo(discordId, guildId, stake = 100L)
            as CasinoHoldemService.DealOutcome.Dealt
        svc.applyAction(discordId, guildId, dealt.tableId, CasinoHoldem.Action.CALL)

        assertTrue(publisher.casinoHoldemWins.isEmpty())
    }
}
