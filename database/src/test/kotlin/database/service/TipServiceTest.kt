package database.service

import database.dto.TipDailyDto
import database.dto.TipLogDto
import database.dto.UserDto
import database.persistence.TipLogPersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class TipServiceTest {

    private val now: Instant = Instant.parse("2026-04-10T10:00:00Z")
    private val today: LocalDate = LocalDate.ofInstant(now, ZoneOffset.UTC)
    private val guildId = 42L
    private val sender = 1L
    private val recipient = 2L

    private lateinit var userService: RecordingUserService
    private lateinit var tipDailyService: InMemoryTipDailyService
    private lateinit var tipLogPersistence: RecordingTipLogPersistence
    private lateinit var service: TipService

    @BeforeEach
    fun setup() {
        userService = RecordingUserService()
        tipDailyService = InMemoryTipDailyService()
        tipLogPersistence = RecordingTipLogPersistence()
        service = TipService(userService, tipDailyService, tipLogPersistence)
    }

    @Test
    fun `tip happy path moves balances and records ledger plus log`() {
        userService.seed(UserDto(sender, guildId).apply { socialCredit = 200L })
        userService.seed(UserDto(recipient, guildId).apply { socialCredit = 50L })

        val outcome = service.tip(sender, recipient, guildId, amount = 75L, note = "thanks", at = now)

        assertTrue(outcome is TipService.TipOutcome.Ok, "expected Ok but got $outcome")
        outcome as TipService.TipOutcome.Ok
        assertEquals(125L, outcome.senderNewBalance)
        assertEquals(125L, outcome.recipientNewBalance)
        assertEquals(75L, outcome.sentTodayAfter)

        assertEquals(125L, userService.current(sender, guildId)?.socialCredit)
        assertEquals(125L, userService.current(recipient, guildId)?.socialCredit)
        assertEquals(75L, tipDailyService.get(sender, guildId, today)?.creditsSent)
        assertEquals(1, tipLogPersistence.inserted.size)
        assertEquals("thanks", tipLogPersistence.inserted[0].note)
    }

    @Test
    fun `tip rejects amount below minimum or above maximum`() {
        userService.seed(UserDto(sender, guildId).apply { socialCredit = 1000L })
        userService.seed(UserDto(recipient, guildId).apply { socialCredit = 0L })

        val tooLow = service.tip(sender, recipient, guildId, amount = TipService.MIN_TIP - 1, at = now)
        val tooHigh = service.tip(sender, recipient, guildId, amount = TipService.MAX_TIP + 1, at = now)

        assertTrue(tooLow is TipService.TipOutcome.InvalidAmount)
        assertTrue(tooHigh is TipService.TipOutcome.InvalidAmount)
        assertEquals(0, tipLogPersistence.inserted.size)
        assertEquals(0, userService.updateCount)
    }

    @Test
    fun `tip rejects self-tip`() {
        userService.seed(UserDto(sender, guildId).apply { socialCredit = 200L })

        val outcome = service.tip(sender, sender, guildId, amount = 50L, at = now)

        assertTrue(outcome is TipService.TipOutcome.InvalidRecipient)
        outcome as TipService.TipOutcome.InvalidRecipient
        assertEquals(TipService.TipOutcome.InvalidRecipient.Reason.SELF, outcome.reason)
        assertEquals(0, userService.updateCount)
    }

    @Test
    fun `tip with insufficient credits writes nothing`() {
        userService.seed(UserDto(sender, guildId).apply { socialCredit = 30L })
        userService.seed(UserDto(recipient, guildId).apply { socialCredit = 0L })

        val outcome = service.tip(sender, recipient, guildId, amount = 100L, at = now)

        assertTrue(outcome is TipService.TipOutcome.InsufficientCredits)
        assertEquals(30L, userService.current(sender, guildId)?.socialCredit)
        assertEquals(0L, userService.current(recipient, guildId)?.socialCredit)
        assertNull(tipDailyService.get(sender, guildId, today))
        assertEquals(0, tipLogPersistence.inserted.size)
        assertEquals(0, userService.updateCount)
    }

    @Test
    fun `tip respects daily cap boundary`() {
        userService.seed(UserDto(sender, guildId).apply { socialCredit = 1000L })
        userService.seed(UserDto(recipient, guildId).apply { socialCredit = 0L })
        // Already sent 480 today against the default 500-credit cap.
        tipDailyService.upsert(TipDailyDto(sender, guildId, today, creditsSent = 480L))

        val rejected = service.tip(sender, recipient, guildId, amount = 50L, at = now)
        assertTrue(rejected is TipService.TipOutcome.DailyCapExceeded, "50 over cap should be rejected")
        assertEquals(0, tipLogPersistence.inserted.size, "no log row when rejected")

        val accepted = service.tip(sender, recipient, guildId, amount = 20L, at = now)
        assertTrue(accepted is TipService.TipOutcome.Ok, "exactly cap should pass")
        assertEquals(500L, tipDailyService.get(sender, guildId, today)?.creditsSent)
    }

    @Test
    fun `tip returns UnknownSender when sender row is missing`() {
        userService.seed(UserDto(recipient, guildId).apply { socialCredit = 0L })

        val outcome = service.tip(sender, recipient, guildId, amount = 50L, at = now)

        assertEquals(TipService.TipOutcome.UnknownSender, outcome)
        assertEquals(0, tipLogPersistence.inserted.size)
    }

    @Test
    fun `tip returns UnknownRecipient when recipient row is missing`() {
        userService.seed(UserDto(sender, guildId).apply { socialCredit = 200L })

        val outcome = service.tip(sender, recipient, guildId, amount = 50L, at = now)

        assertEquals(TipService.TipOutcome.UnknownRecipient, outcome)
        assertEquals(0, tipLogPersistence.inserted.size)
    }

    @Test
    fun `tip locks both users in ascending discord-id order regardless of direction`() {
        userService.seed(UserDto(sender, guildId).apply { socialCredit = 200L })
        userService.seed(UserDto(recipient, guildId).apply { socialCredit = 50L })

        // sender (1) → recipient (2): lock order should be 1, 2.
        service.tip(sender, recipient, guildId, amount = 20L, at = now)
        assertEquals(listOf(sender, recipient), userService.lockOrder)

        // Reset and try the reverse direction.
        userService.lockOrder.clear()
        service.tip(recipient, sender, guildId, amount = 20L, at = now)
        // sender (1) is still the lower id even when we tip from recipient → sender.
        assertEquals(listOf(sender, recipient), userService.lockOrder)
    }

    @Test
    fun `tip truncates note to MAX_NOTE_LENGTH`() {
        userService.seed(UserDto(sender, guildId).apply { socialCredit = 200L })
        userService.seed(UserDto(recipient, guildId).apply { socialCredit = 0L })

        val longNote = "x".repeat(TipService.MAX_NOTE_LENGTH + 50)
        val outcome = service.tip(sender, recipient, guildId, amount = 10L, note = longNote, at = now)

        assertTrue(outcome is TipService.TipOutcome.Ok)
        assertNotNull(tipLogPersistence.inserted[0].note)
        assertEquals(TipService.MAX_NOTE_LENGTH, tipLogPersistence.inserted[0].note!!.length)
    }

    private class RecordingUserService : UserService {
        private val users = mutableMapOf<Pair<Long, Long>, UserDto>()
        var updateCount: Int = 0
            private set
        val lockOrder: MutableList<Long> = mutableListOf()

        fun seed(dto: UserDto) {
            users[dto.discordId to dto.guildId] = dto
        }

        fun current(discordId: Long, guildId: Long): UserDto? = users[discordId to guildId]

        override fun listGuildUsers(guildId: Long?): List<UserDto?> = users.values.filter { it.guildId == guildId }
        override fun createNewUser(userDto: UserDto): UserDto = userDto.also(::seed)
        override fun getUserById(discordId: Long?, guildId: Long?): UserDto? =
            users[discordId!! to guildId!!]

        override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? {
            lockOrder.add(discordId!!)
            return getUserById(discordId, guildId)
        }

        override fun updateUser(userDto: UserDto): UserDto {
            updateCount++
            seed(userDto)
            return userDto
        }

        override fun deleteUser(userDto: UserDto) { users.remove(userDto.discordId to userDto.guildId) }
        override fun deleteUserById(discordId: Long?, guildId: Long?) { users.remove(discordId!! to guildId!!) }
        override fun clearCache() {}
        override fun evictUserFromCache(discordId: Long?, guildId: Long?) {}
    }

    private class InMemoryTipDailyService : TipDailyService {
        private val rows = mutableMapOf<Triple<Long, Long, LocalDate>, TipDailyDto>()
        override fun get(senderDiscordId: Long, guildId: Long, date: LocalDate): TipDailyDto? =
            rows[Triple(senderDiscordId, guildId, date)]
        override fun upsert(row: TipDailyDto): TipDailyDto {
            rows[Triple(row.senderDiscordId, row.guildId, row.tipDate)] = row
            return row
        }
    }

    private class RecordingTipLogPersistence : TipLogPersistence {
        val inserted: MutableList<TipLogDto> = mutableListOf()
        override fun insert(row: TipLogDto): TipLogDto { inserted.add(row); return row }
    }
}
