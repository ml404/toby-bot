package database.service

import database.dto.ConfigDto
import database.dto.PokerHandLogDto
import database.dto.PokerHandPotDto
import database.dto.UserDto
import database.persistence.PokerHandLogPersistence
import database.persistence.PokerHandPotPersistence
import database.poker.PokerEngine
import database.poker.PokerTable
import database.poker.PokerTableRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.random.Random

class PokerServiceTest {

    private val guildId = 42L
    private val host = 1L
    private val joiner = 2L
    private val third = 3L

    private lateinit var userService: RecordingUserService
    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService
    private lateinit var registry: PokerTableRegistry
    private lateinit var handLog: RecordingPokerHandLogPersistence
    private lateinit var handPot: RecordingPokerHandPotPersistence
    private lateinit var service: PokerService

    @BeforeEach
    fun setup() {
        userService = RecordingUserService()
        jackpotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        registry = PokerTableRegistry(
            idleTtl = Duration.ofMinutes(5),
            sweepInterval = Duration.ofHours(1)
        )
        handLog = RecordingPokerHandLogPersistence()
        handPot = RecordingPokerHandPotPersistence()
        // Default config — fall back to 5% rake.
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.POKER_RAKE_PCT.configValue,
                guildId.toString()
            )
        } returns null
        service = PokerService(
            userService = userService,
            jackpotService = jackpotService,
            configService = configService,
            tableRegistry = registry,
            handLogPersistence = handLog,
            handPotPersistence = handPot,
            random = Random(42)
        )
    }

    private fun seed(discordId: Long, balance: Long) {
        userService.seed(UserDto(discordId, guildId).apply { socialCredit = balance })
    }

    @Test
    fun `createTable debits credits and seats the host with chip escrow`() {
        seed(host, 1000L)

        val outcome = service.createTable(host, guildId, buyIn = 200L)

        assertTrue(outcome is PokerService.CreateOutcome.Ok)
        val tableId = (outcome as PokerService.CreateOutcome.Ok).tableId
        val table = registry.get(tableId)!!
        assertEquals(1, table.seats.size)
        assertEquals(host, table.seats[0].discordId)
        assertEquals(200L, table.seats[0].chips)
        assertEquals(800L, userService.current(host)?.socialCredit, "200 debited from balance")
    }

    @Test
    fun `createTable rejects invalid buy-in without seating or debiting`() {
        seed(host, 1000L)
        val outcome = service.createTable(host, guildId, buyIn = 1L)
        assertTrue(outcome is PokerService.CreateOutcome.InvalidBuyIn)
        assertEquals(0, registry.listForGuild(guildId).size)
        assertEquals(1000L, userService.current(host)?.socialCredit)
    }

    @Test
    fun `createTable rejects insufficient credits without seating`() {
        seed(host, 50L)
        val outcome = service.createTable(host, guildId, buyIn = 200L)
        assertTrue(outcome is PokerService.CreateOutcome.InsufficientCredits)
        assertEquals(0, registry.listForGuild(guildId).size)
        assertEquals(50L, userService.current(host)?.socialCredit)
    }

    @Test
    fun `buyIn debits credits and seats the player`() {
        seed(host, 1000L)
        seed(joiner, 600L)
        val createOutcome = service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok

        val outcome = service.buyIn(joiner, guildId, createOutcome.tableId, buyIn = 300L)

        assertTrue(outcome is PokerService.BuyInOutcome.Ok)
        outcome as PokerService.BuyInOutcome.Ok
        assertEquals(300L, userService.current(joiner)?.socialCredit)
        assertEquals(2, registry.get(createOutcome.tableId)!!.seats.size)
        assertEquals(300L, outcome.newBalance)
    }

    @Test
    fun `buyIn rejects double-seating`() {
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        val outcome = service.buyIn(host, guildId, tableId, buyIn = 200L)
        assertEquals(PokerService.BuyInOutcome.AlreadySeated, outcome)
    }

    @Test
    fun `buyIn rejects unknown table`() {
        seed(joiner, 1000L)
        val outcome = service.buyIn(joiner, guildId, tableId = 999L, buyIn = 200L)
        assertEquals(PokerService.BuyInOutcome.TableNotFound, outcome)
    }

    @Test
    fun `cashOut credits remaining chips back to balance and removes empty tables`() {
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId

        val outcome = service.cashOut(host, guildId, tableId)

        assertTrue(outcome is PokerService.CashOutOutcome.Ok)
        outcome as PokerService.CashOutOutcome.Ok
        assertEquals(200L, outcome.chipsReturned)
        assertEquals(1000L, outcome.newBalance, "balance restored to original 1000")
        assertNull(registry.get(tableId), "empty table dropped from registry")
    }

    @Test
    fun `cashOut while hand in progress is rejected`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)
        service.startHand(host, guildId, tableId)

        val outcome = service.cashOut(host, guildId, tableId)
        assertEquals(PokerService.CashOutOutcome.HandInProgress, outcome)
    }

    @Test
    fun `cashOut on table you don't sit at is rejected`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        val outcome = service.cashOut(joiner, guildId, tableId)
        assertEquals(PokerService.CashOutOutcome.NotSeated, outcome)
    }

    @Test
    fun `startHand requires host`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)

        val byJoiner = service.startHand(joiner, guildId, tableId)
        assertEquals(PokerService.StartHandOutcome.NotHost, byJoiner)

        val byHost = service.startHand(host, guildId, tableId)
        assertTrue(byHost is PokerService.StartHandOutcome.Ok)
        assertEquals(1L, (byHost as PokerService.StartHandOutcome.Ok).handNumber)
    }

    @Test
    fun `applyAction routes rake to jackpot and persists hand log on resolution`() {
        seed(host, 1000L); seed(joiner, 1000L); seed(third, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)
        service.buyIn(third, guildId, tableId, buyIn = 500L)
        service.startHand(host, guildId, tableId)

        val table = registry.get(tableId)!!
        // Make all 3 players fold around — winner takes uncontested pot.
        // Apply two folds to leave one player. Pot is SB+BB=15, rake=floor(15*0.05)=0.
        val firstActor = table.seats[table.actorIndex].discordId
        service.applyAction(firstActor, guildId, tableId, PokerEngine.PokerAction.Fold)
        val secondActor = table.seats[table.actorIndex].discordId
        val outcome = service.applyAction(secondActor, guildId, tableId, PokerEngine.PokerAction.Fold)

        assertTrue(outcome is PokerService.ActionOutcome.HandResolved)
        // Tiny pot, rake floors to 0, so jackpot service NOT called.
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
        assertEquals(1, handLog.inserted.size, "hand log row written")
        assertEquals(15L, handLog.inserted[0].pot)
        assertEquals(0L, handLog.inserted[0].rake)
    }

    @Test
    fun `applyAction with raise grows pot enough that rake routes to jackpot`() {
        seed(host, 5000L); seed(joiner, 5000L)
        val tableId = (service.createTable(host, guildId, buyIn = 2000L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 2000L)
        service.startHand(host, guildId, tableId)

        val table = registry.get(tableId)!!
        // HU preflop: dealer (SB) acts first.
        val sbId = table.seats[table.dealerIndex].discordId
        val bbId = table.seats[(table.dealerIndex + 1) % 2].discordId
        // SB raises to 20 → BB raises to 30 → SB raises to 40 → BB raises to 50 (cap) → SB calls.
        service.applyAction(sbId, guildId, tableId, PokerEngine.PokerAction.Raise)
        service.applyAction(bbId, guildId, tableId, PokerEngine.PokerAction.Raise)
        service.applyAction(sbId, guildId, tableId, PokerEngine.PokerAction.Raise)
        service.applyAction(bbId, guildId, tableId, PokerEngine.PokerAction.Raise)
        service.applyAction(sbId, guildId, tableId, PokerEngine.PokerAction.Call)

        // Now flop, turn, river — both check.
        for (street in 1..3) {
            val a = registry.get(tableId)!!.seats[registry.get(tableId)!!.actorIndex].discordId
            service.applyAction(a, guildId, tableId, PokerEngine.PokerAction.Check)
            val b = registry.get(tableId)!!.seats[registry.get(tableId)!!.actorIndex].discordId
            service.applyAction(b, guildId, tableId, PokerEngine.PokerAction.Check)
        }

        // Pot = 100 (50 each), rake = floor(100 * 0.05) = 5.
        verify(exactly = 1) { jackpotService.addToPool(guildId, 5L) }
        assertEquals(1, handLog.inserted.size)
        assertEquals(100L, handLog.inserted[0].pot)
        assertEquals(5L, handLog.inserted[0].rake)
        // v2: single-pot hand still produces exactly one pot-tier row
        // joined to the hand log so the audit table is consistent
        // whether or not side pots formed.
        val handLogId = handLog.inserted[0].id!!
        val tiers = handPot.findByHandLogId(handLogId)
        assertEquals(1, tiers.size, "single-pot hand → single audit tier row")
        assertEquals(0, tiers[0].tierIndex)
        assertEquals(95L, tiers[0].amount, "tier amount net of rake (100 - 5)")
    }

    @Test
    fun `applyAction NotYourTurn surfaces engine rejection`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 500L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 500L)
        service.startHand(host, guildId, tableId)

        val table = registry.get(tableId)!!
        val notTurn = table.seats.first { table.seats.indexOf(it) != table.actorIndex }.discordId
        val outcome = service.applyAction(notTurn, guildId, tableId, PokerEngine.PokerAction.Check)
        assertTrue(outcome is PokerService.ActionOutcome.Rejected)
        assertEquals(PokerEngine.RejectReason.NOT_YOUR_TURN, (outcome as PokerService.ActionOutcome.Rejected).reason)
    }

    @Test
    fun `applyAction on missing table returns TableNotFound`() {
        val outcome = service.applyAction(host, guildId, tableId = 999L, action = PokerEngine.PokerAction.Check)
        assertEquals(PokerService.ActionOutcome.TableNotFound, outcome)
    }

    @Test
    fun `evictAllSeats refunds chips to all seated players`() {
        seed(host, 1000L); seed(joiner, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        service.buyIn(joiner, guildId, tableId, buyIn = 300L)
        val table = registry.get(tableId)!!

        service.evictAllSeats(table)

        assertEquals(1000L, userService.current(host)?.socialCredit, "host refunded 200")
        assertEquals(1000L, userService.current(joiner)?.socialCredit, "joiner refunded 300")
        assertEquals(0, table.seats.size)
    }

    @Test
    fun `rakeRate falls back to default when config unset and clamps to MAX`() {
        // Default
        assertEquals(0.05, service.rakeRate(guildId), 0.0001)
        // Unparseable → default
        every {
            configService.getConfigByName(ConfigDto.Configurations.POKER_RAKE_PCT.configValue, guildId.toString())
        } returns ConfigDto(name = "x", value = "asdf", guildId = guildId.toString())
        assertEquals(0.05, service.rakeRate(guildId), 0.0001)
        // Above max → clamped
        every {
            configService.getConfigByName(ConfigDto.Configurations.POKER_RAKE_PCT.configValue, guildId.toString())
        } returns ConfigDto(name = "x", value = "99", guildId = guildId.toString())
        assertEquals(PokerService.MAX_RAKE, service.rakeRate(guildId), 0.0001)
        // Sane custom value
        every {
            configService.getConfigByName(ConfigDto.Configurations.POKER_RAKE_PCT.configValue, guildId.toString())
        } returns ConfigDto(name = "x", value = "10", guildId = guildId.toString())
        assertEquals(0.10, service.rakeRate(guildId), 0.0001)
    }

    @Test
    fun `wireRegistry hooks the idle-evict callback`() {
        // Sanity check that the registry would actually call our refund logic
        // when sweep evicts a table. We exercise the wired callback explicitly
        // since the scheduler runs on a long interval in tests.
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        service.wireRegistry()
        // Force an eviction by backdating activity and sweeping.
        registry.get(tableId)!!.lastActivityAt = java.time.Instant.now().minus(Duration.ofHours(1))
        registry.sweepIdle(java.time.Instant.now())
        assertEquals(1000L, userService.current(host)?.socialCredit, "evicted chips refunded via callback")
        assertNull(registry.get(tableId), "table removed by sweep")
    }

    @Test
    fun `snapshot returns the live table reference`() {
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        val snap = service.snapshot(tableId)
        assertNotNull(snap)
        assertEquals(tableId, snap!!.id)
        assertNull(service.snapshot(999L))
    }

    @Test
    fun `cashOut on an unknown table returns TableNotFound`() {
        val outcome = service.cashOut(host, guildId, tableId = 999L)
        assertEquals(PokerService.CashOutOutcome.TableNotFound, outcome)
    }

    @Test
    fun `startHand on missing table returns TableNotFound`() {
        val outcome = service.startHand(host, guildId, tableId = 999L)
        assertEquals(PokerService.StartHandOutcome.TableNotFound, outcome)
    }

    @Test
    fun `buyIn rejects mismatched guild as TableNotFound`() {
        seed(host, 1000L)
        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        // Wrong guild id should not let a player from another server peek into this guild's table.
        val outcome = service.buyIn(joiner, guildId = 999L, tableId = tableId, buyIn = 200L)
        assertEquals(PokerService.BuyInOutcome.TableNotFound, outcome)
        assertFalse(registry.get(tableId)!!.seats.any { it.discordId == joiner })
    }

    private class RecordingUserService : UserService {
        private val users = mutableMapOf<Pair<Long, Long>, UserDto>()
        var updateCount = 0
            private set
        fun seed(dto: UserDto) { users[dto.discordId to dto.guildId] = dto }
        fun current(discordId: Long, guildId: Long = 42L): UserDto? = users[discordId to guildId]

        override fun listGuildUsers(guildId: Long?): List<UserDto?> = users.values.filter { it.guildId == guildId }
        override fun createNewUser(userDto: UserDto): UserDto = userDto.also(::seed)
        override fun getUserById(discordId: Long?, guildId: Long?): UserDto? = users[discordId!! to guildId!!]
        override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? =
            users[discordId!! to guildId!!]
        override fun updateUser(userDto: UserDto): UserDto { updateCount++; users[userDto.discordId to userDto.guildId] = userDto; return userDto }
        override fun deleteUser(userDto: UserDto) { users.remove(userDto.discordId to userDto.guildId) }
        override fun deleteUserById(discordId: Long?, guildId: Long?) { users.remove(discordId!! to guildId!!) }
        override fun clearCache() {}
        override fun evictUserFromCache(discordId: Long?, guildId: Long?) {}
    }

    private class RecordingPokerHandLogPersistence : PokerHandLogPersistence {
        val inserted = mutableListOf<PokerHandLogDto>()
        private var nextId = 1L
        override fun insert(row: PokerHandLogDto): PokerHandLogDto {
            // Production assigns id via @GeneratedValue + flush. Stub it
            // here so persistResult can pass it on to the pot rows.
            if (row.id == null) row.id = nextId++
            inserted.add(row); return row
        }
    }

    private class RecordingPokerHandPotPersistence : PokerHandPotPersistence {
        val inserted = mutableListOf<PokerHandPotDto>()
        override fun insert(row: PokerHandPotDto): PokerHandPotDto {
            inserted.add(row); return row
        }
        override fun findByHandLogId(handLogId: Long): List<PokerHandPotDto> =
            inserted.filter { it.handLogId == handLogId }.sortedBy { it.tierIndex }
    }
}
