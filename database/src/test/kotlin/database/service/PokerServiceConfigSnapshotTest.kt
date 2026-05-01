package database.service

import database.dto.ConfigDto
import database.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import database.persistence.PokerHandLogPersistence
import database.persistence.PokerHandPotPersistence
import database.dto.PokerHandLogDto
import database.dto.PokerHandPotDto
import database.poker.PokerTableRegistry
import java.time.Duration
import kotlin.random.Random

/**
 * Verifies that [PokerService.createTable] reads the per-guild
 * poker config and snapshots those values onto the new table —
 * subsequent admin tweaks must not bleed into an in-flight table.
 */
class PokerServiceConfigSnapshotTest {

    private val guildId = 99L
    private val host = 1L

    private lateinit var userService: RecordingUserService
    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService
    private lateinit var registry: PokerTableRegistry
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
        service = PokerService(
            userService = userService,
            jackpotService = jackpotService,
            configService = configService,
            tableRegistry = registry,
            handLogPersistence = NoopHandLog(),
            handPotPersistence = NoopHandPot(),
            random = Random(0)
        )
        // Default — every config key returns null so the service falls
        // back to its compile-time defaults. Specific tests override.
        every { configService.getConfigByName(any(), guildId.toString()) } returns null
    }

    private fun seedConfig(key: ConfigDto.Configurations, value: String) {
        every {
            configService.getConfigByName(key.configValue, guildId.toString())
        } returns ConfigDto(name = key.configValue, value = value, guildId = guildId.toString())
    }

    @Test
    fun `createTable falls back to defaults when no guild config is set`() {
        userService.seed(UserDto(host, guildId).apply { socialCredit = 5000L })
        val outcome = service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok
        val table = registry.get(outcome.tableId)!!
        assertEquals(PokerService.SMALL_BLIND, table.smallBlind)
        assertEquals(PokerService.BIG_BLIND, table.bigBlind)
        assertEquals(PokerService.SMALL_BET, table.smallBet)
        assertEquals(PokerService.BIG_BET, table.bigBet)
        assertEquals(PokerService.MIN_BUY_IN, table.minBuyIn)
        assertEquals(PokerService.MAX_BUY_IN, table.maxBuyIn)
        assertEquals(PokerService.MAX_SEATS, table.maxSeats)
        assertEquals(PokerService.DEFAULT_SHOT_CLOCK_SECONDS, table.shotClockSeconds)
    }

    @Test
    fun `createTable reads each per-guild key and snapshots them on the table`() {
        seedConfig(ConfigDto.Configurations.POKER_SMALL_BLIND, "25")
        seedConfig(ConfigDto.Configurations.POKER_BIG_BLIND, "50")
        seedConfig(ConfigDto.Configurations.POKER_SMALL_BET, "50")
        seedConfig(ConfigDto.Configurations.POKER_BIG_BET, "100")
        seedConfig(ConfigDto.Configurations.POKER_MIN_BUY_IN, "1000")
        seedConfig(ConfigDto.Configurations.POKER_MAX_BUY_IN, "20000")
        seedConfig(ConfigDto.Configurations.POKER_MAX_SEATS, "9")
        seedConfig(ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS, "60")
        userService.seed(UserDto(host, guildId).apply { socialCredit = 50_000L })

        val outcome = service.createTable(host, guildId, buyIn = 5000L) as PokerService.CreateOutcome.Ok
        val table = registry.get(outcome.tableId)!!

        assertEquals(25L, table.smallBlind)
        assertEquals(50L, table.bigBlind)
        assertEquals(50L, table.smallBet)
        assertEquals(100L, table.bigBet)
        assertEquals(1000L, table.minBuyIn)
        assertEquals(20000L, table.maxBuyIn)
        assertEquals(9, table.maxSeats)
        assertEquals(60, table.shotClockSeconds)
    }

    @Test
    fun `createTable rejects buy-in outside the per-guild bounds`() {
        seedConfig(ConfigDto.Configurations.POKER_MIN_BUY_IN, "500")
        seedConfig(ConfigDto.Configurations.POKER_MAX_BUY_IN, "1000")
        userService.seed(UserDto(host, guildId).apply { socialCredit = 5000L })

        // Below the per-guild floor — would have passed under the
        // hardcoded default of 100.
        val low = service.createTable(host, guildId, buyIn = 200L)
        assertTrue(low is PokerService.CreateOutcome.InvalidBuyIn,
            "expected per-guild floor to reject 200, got $low")
        low as PokerService.CreateOutcome.InvalidBuyIn
        assertEquals(500L, low.min)
        assertEquals(1000L, low.max)

        // Within the per-guild window.
        val ok = service.createTable(host, guildId, buyIn = 600L)
        assertTrue(ok is PokerService.CreateOutcome.Ok)
    }

    @Test
    fun `mid-hand admin tweak does not affect an in-flight table`() {
        seedConfig(ConfigDto.Configurations.POKER_SMALL_BLIND, "5")
        seedConfig(ConfigDto.Configurations.POKER_BIG_BLIND, "10")
        seedConfig(ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS, "15")
        userService.seed(UserDto(host, guildId).apply { socialCredit = 5000L })

        val first = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        val firstTable = registry.get(first)!!

        // Admin doubles the blinds while the table is sitting in WAITING.
        seedConfig(ConfigDto.Configurations.POKER_SMALL_BLIND, "100")
        seedConfig(ConfigDto.Configurations.POKER_BIG_BLIND, "200")
        seedConfig(ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS, "5")

        // The first table keeps its snapshot — players who joined under
        // the old limits don't get blindsided mid-session.
        assertEquals(5L, firstTable.smallBlind)
        assertEquals(10L, firstTable.bigBlind)
        assertEquals(15, firstTable.shotClockSeconds)

        // A *new* table picks up the new values.
        userService.seed(UserDto(host + 1, guildId).apply { socialCredit = 5000L })
        val secondId = (service.createTable(host + 1, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        val secondTable = registry.get(secondId)!!
        assertEquals(100L, secondTable.smallBlind)
        assertEquals(200L, secondTable.bigBlind)
        assertEquals(5, secondTable.shotClockSeconds)
    }

    @Test
    fun `unparseable config falls back to default rather than blowing up`() {
        seedConfig(ConfigDto.Configurations.POKER_SMALL_BLIND, "asdf")
        seedConfig(ConfigDto.Configurations.POKER_MAX_SEATS, "not-a-number")
        seedConfig(ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS, "")
        userService.seed(UserDto(host, guildId).apply { socialCredit = 5000L })

        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        val table = registry.get(tableId)!!
        assertEquals(PokerService.SMALL_BLIND, table.smallBlind)
        assertEquals(PokerService.MAX_SEATS, table.maxSeats)
        assertEquals(PokerService.DEFAULT_SHOT_CLOCK_SECONDS, table.shotClockSeconds)
    }

    @Test
    fun `out-of-range integer config is clamped to the allowed range`() {
        seedConfig(ConfigDto.Configurations.POKER_MAX_SEATS, "20") // above max
        seedConfig(ConfigDto.Configurations.POKER_SHOT_CLOCK_SECONDS, "9999") // above max
        userService.seed(UserDto(host, guildId).apply { socialCredit = 5000L })

        val tableId = (service.createTable(host, guildId, buyIn = 200L) as PokerService.CreateOutcome.Ok).tableId
        val table = registry.get(tableId)!!
        assertEquals(9, table.maxSeats, "clamped to upper bound 9")
        assertEquals(600, table.shotClockSeconds, "clamped to upper bound 600s")
    }

    private class NoopHandLog : PokerHandLogPersistence {
        override fun insert(row: PokerHandLogDto): PokerHandLogDto {
            row.id = 1L; return row
        }
        override fun findRecentByTable(guildId: Long, tableId: Long, limit: Int): List<PokerHandLogDto> = emptyList()
        override fun findRecentByGuild(guildId: Long, limit: Int): List<PokerHandLogDto> = emptyList()
    }

    private class NoopHandPot : PokerHandPotPersistence {
        override fun insert(row: PokerHandPotDto): PokerHandPotDto = row
        override fun findByHandLogId(handLogId: Long): List<PokerHandPotDto> = emptyList()
    }

    private class RecordingUserService : UserService {
        private val users = mutableMapOf<Pair<Long, Long>, UserDto>()
        fun seed(dto: UserDto) { users[dto.discordId to dto.guildId] = dto }
        override fun listGuildUsers(guildId: Long?): List<UserDto?> = users.values.filter { it.guildId == guildId }
        override fun createNewUser(userDto: UserDto): UserDto = userDto.also(::seed)
        override fun getUserById(discordId: Long?, guildId: Long?): UserDto? = users[discordId!! to guildId!!]
        override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? = users[discordId!! to guildId!!]
        override fun updateUser(userDto: UserDto): UserDto { users[userDto.discordId to userDto.guildId] = userDto; return userDto }
        override fun deleteUser(userDto: UserDto) { users.remove(userDto.discordId to userDto.guildId) }
        override fun deleteUserById(discordId: Long?, guildId: Long?) { users.remove(discordId!! to guildId!!) }
        override fun clearCache() {}
        override fun evictUserFromCache(discordId: Long?, guildId: Long?) {}
    }
}
