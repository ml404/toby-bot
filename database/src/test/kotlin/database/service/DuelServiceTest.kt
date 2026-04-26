package database.service

import database.dto.ConfigDto
import database.dto.DuelLogDto
import database.dto.UserDto
import database.persistence.DuelLogPersistence
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

class DuelServiceTest {

    private val now: Instant = Instant.parse("2026-04-10T10:00:00Z")
    private val guildId = 42L
    private val initiator = 1L
    private val opponent = 2L

    private lateinit var userService: RecordingUserService
    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService
    private lateinit var duelLogPersistence: RecordingDuelLogPersistence

    @BeforeEach
    fun setup() {
        userService = RecordingUserService()
        jackpotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        duelLogPersistence = RecordingDuelLogPersistence()

        // Default tribute config: empty → JackpotHelper falls back to 10%.
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue,
                guildId.toString()
            )
        } returns null
    }

    private fun service(random: Random = AlwaysHeadsRandom): DuelService =
        DuelService(userService, jackpotService, configService, duelLogPersistence, random)

    @Test
    fun `startDuel returns Ok when both players have enough credits`() {
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 200L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 200L })

        val outcome = service().startDuel(initiator, opponent, guildId, stake = 50L)

        assertTrue(outcome is DuelService.StartOutcome.Ok)
        assertEquals(0, userService.updateCount, "startDuel must not debit anyone")
    }

    @Test
    fun `startDuel rejects invalid stake`() {
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 1000L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 1000L })

        val tooLow = service().startDuel(initiator, opponent, guildId, stake = 5L)
        val tooHigh = service().startDuel(initiator, opponent, guildId, stake = 10_000L)
        assertTrue(tooLow is DuelService.StartOutcome.InvalidStake)
        assertTrue(tooHigh is DuelService.StartOutcome.InvalidStake)
    }

    @Test
    fun `startDuel rejects self-duel`() {
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 1000L })

        val outcome = service().startDuel(initiator, initiator, guildId, stake = 50L)

        assertTrue(outcome is DuelService.StartOutcome.InvalidOpponent)
    }

    @Test
    fun `startDuel reports initiator and opponent insufficiency separately`() {
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 5L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 1000L })

        val initiatorLow = service().startDuel(initiator, opponent, guildId, stake = 50L)
        assertTrue(initiatorLow is DuelService.StartOutcome.InitiatorInsufficient)

        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 1000L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 5L })
        val opponentLow = service().startDuel(initiator, opponent, guildId, stake = 50L)
        assertTrue(opponentLow is DuelService.StartOutcome.OpponentInsufficient)
    }

    @Test
    fun `acceptDuel debits both players, credits the winner pot minus tribute, and logs`() {
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 200L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 100L })

        // AlwaysHeadsRandom → initiator wins.
        val outcome = service().acceptDuel(initiator, opponent, guildId, stake = 50L, at = now)

        assertTrue(outcome is DuelService.AcceptOutcome.Win)
        outcome as DuelService.AcceptOutcome.Win
        assertEquals(initiator, outcome.winnerDiscordId)
        assertEquals(opponent, outcome.loserDiscordId)
        assertEquals(100L, outcome.pot)
        assertEquals(5L, outcome.lossTribute, "10% of 50 = 5")
        // Initiator: 200 - 50 (stake) + 95 (pot - tribute) = 245
        assertEquals(245L, outcome.winnerNewBalance)
        // Opponent: 100 - 50 = 50
        assertEquals(50L, outcome.loserNewBalance)

        assertEquals(245L, userService.current(initiator, guildId)?.socialCredit)
        assertEquals(50L, userService.current(opponent, guildId)?.socialCredit)
        assertEquals(1, duelLogPersistence.inserted.size)
        assertEquals(5L, duelLogPersistence.inserted[0].lossTribute)
        assertEquals(100L, duelLogPersistence.inserted[0].pot)
    }

    @Test
    fun `acceptDuel awards opponent when RNG says so`() {
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 200L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 100L })

        val outcome = service(AlwaysTailsRandom).acceptDuel(initiator, opponent, guildId, stake = 50L, at = now)

        assertTrue(outcome is DuelService.AcceptOutcome.Win)
        outcome as DuelService.AcceptOutcome.Win
        assertEquals(opponent, outcome.winnerDiscordId)
        assertEquals(initiator, outcome.loserDiscordId)
        // Opponent: 100 - 50 (stake) + 95 (pot - tribute) = 145
        assertEquals(145L, outcome.winnerNewBalance)
        // Initiator: 200 - 50 = 150
        assertEquals(150L, outcome.loserNewBalance)
    }

    @Test
    fun `acceptDuel returns InitiatorInsufficient and writes nothing when initiator balance dropped`() {
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 30L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 100L })

        val outcome = service().acceptDuel(initiator, opponent, guildId, stake = 50L, at = now)

        assertTrue(outcome is DuelService.AcceptOutcome.InitiatorInsufficient)
        assertEquals(0, duelLogPersistence.inserted.size)
        assertEquals(0, userService.updateCount)
    }

    @Test
    fun `acceptDuel returns OpponentInsufficient when opponent balance dropped`() {
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 100L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 30L })

        val outcome = service().acceptDuel(initiator, opponent, guildId, stake = 50L, at = now)

        assertTrue(outcome is DuelService.AcceptOutcome.OpponentInsufficient)
        assertEquals(0, duelLogPersistence.inserted.size)
        assertEquals(0, userService.updateCount)
    }

    @Test
    fun `acceptDuel tribute floors small stakes correctly`() {
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 50L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 50L })

        val outcome10 = service().acceptDuel(initiator, opponent, guildId, stake = 10L, at = now)
        assertTrue(outcome10 is DuelService.AcceptOutcome.Win)
        assertEquals(1L, (outcome10 as DuelService.AcceptOutcome.Win).lossTribute, "floor(10 * 0.10) = 1")

        // Reset and re-test with stake = 7 (acceptDuel itself doesn't gate
        // stake bounds — startDuel does — so we can probe the floor=0 path).
        userService = RecordingUserService()
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 50L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 50L })
        val outcome7 = service().acceptDuel(initiator, opponent, guildId, stake = 7L, at = now)
        assertTrue(outcome7 is DuelService.AcceptOutcome.Win)
        assertEquals(0L, (outcome7 as DuelService.AcceptOutcome.Win).lossTribute, "floor(7 * 0.10) = 0")
    }

    @Test
    fun `acceptDuel locks both users in ascending discord-id order`() {
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 200L })
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 200L })

        service().acceptDuel(initiator, opponent, guildId, stake = 50L, at = now)
        assertEquals(listOf(initiator, opponent), userService.lockOrder)

        userService.lockOrder.clear()
        // Reverse direction — locks should still be by ascending id.
        service().acceptDuel(opponent, initiator, guildId, stake = 50L, at = now)
        assertEquals(listOf(initiator, opponent), userService.lockOrder)
    }

    @Test
    fun `acceptDuel returns UnknownInitiator and UnknownOpponent for missing rows`() {
        // Only opponent seeded.
        userService.seed(UserDto(opponent, guildId).apply { socialCredit = 200L })
        assertEquals(
            DuelService.AcceptOutcome.UnknownInitiator,
            service().acceptDuel(initiator, opponent, guildId, stake = 50L, at = now)
        )

        // Reset; only initiator.
        userService = RecordingUserService()
        userService.seed(UserDto(initiator, guildId).apply { socialCredit = 200L })
        assertEquals(
            DuelService.AcceptOutcome.UnknownOpponent,
            service().acceptDuel(initiator, opponent, guildId, stake = 50L, at = now)
        )
    }

    private object AlwaysHeadsRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextBoolean(): Boolean = true
    }

    private object AlwaysTailsRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextBoolean(): Boolean = false
    }

    private class RecordingUserService : UserService {
        private val users = mutableMapOf<Pair<Long, Long>, UserDto>()
        var updateCount: Int = 0
            private set
        val lockOrder: MutableList<Long> = mutableListOf()

        fun seed(dto: UserDto) { users[dto.discordId to dto.guildId] = dto }
        fun current(discordId: Long, guildId: Long): UserDto? = users[discordId to guildId]

        override fun listGuildUsers(guildId: Long?): List<UserDto?> = users.values.filter { it.guildId == guildId }
        override fun createNewUser(userDto: UserDto): UserDto = userDto.also(::seed)
        override fun getUserById(discordId: Long?, guildId: Long?): UserDto? = users[discordId!! to guildId!!]
        override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? {
            lockOrder.add(discordId!!)
            return users[discordId to guildId!!]
        }
        override fun updateUser(userDto: UserDto): UserDto { updateCount++; users[userDto.discordId to userDto.guildId] = userDto; return userDto }
        override fun deleteUser(userDto: UserDto) { users.remove(userDto.discordId to userDto.guildId) }
        override fun deleteUserById(discordId: Long?, guildId: Long?) { users.remove(discordId!! to guildId!!) }
        override fun clearCache() {}
        override fun evictUserFromCache(discordId: Long?, guildId: Long?) {}
    }

    private class RecordingDuelLogPersistence : DuelLogPersistence {
        val inserted: MutableList<DuelLogDto> = mutableListOf()
        override fun insert(row: DuelLogDto): DuelLogDto { inserted.add(row); return row }
    }
}
