package database.service

import database.dto.UserDto
import database.dto.VoiceCreditDailyDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class SocialCreditAwardServiceTest {

    private val discordId = 1L
    private val guildId = 42L
    private val now: Instant = Instant.parse("2026-04-10T10:00:00Z")
    private val today: LocalDate = LocalDate.ofInstant(now, ZoneOffset.UTC)

    private lateinit var userService: RecordingUserService
    private lateinit var dailyService: InMemoryVoiceCreditDailyService
    private lateinit var service: SocialCreditAwardService

    @BeforeEach
    fun setup() {
        userService = RecordingUserService()
        dailyService = InMemoryVoiceCreditDailyService()
        service = SocialCreditAwardService(userService, dailyService)
    }

    @Test
    fun `award adds credits and persists via updateUser`() {
        userService.seed(UserDto(discordId, guildId).apply { socialCredit = 10L })

        val granted = service.award(discordId, guildId, amount = 5L, reason = "test", at = now)

        assertEquals(5L, granted)
        assertEquals(15L, userService.current(discordId, guildId)?.socialCredit)
        assertEquals(1, userService.updateCount)
    }

    @Test
    fun `award returns zero and no-ops for non-positive amounts`() {
        userService.seed(UserDto(discordId, guildId).apply { socialCredit = 10L })

        assertEquals(0L, service.award(discordId, guildId, amount = 0L, reason = "test", at = now))
        assertEquals(0L, service.award(discordId, guildId, amount = -3L, reason = "test", at = now))

        assertEquals(10L, userService.current(discordId, guildId)?.socialCredit)
        assertEquals(0, userService.updateCount)
    }

    @Test
    fun `award respects the daily cap and consumes the remaining headroom only`() {
        userService.seed(UserDto(discordId, guildId).apply { socialCredit = 0L })
        // 55/60 already used today.
        dailyService.upsert(VoiceCreditDailyDto(discordId, guildId, today, credits = 55L))

        val granted = service.award(discordId, guildId, amount = 20L, reason = "test", at = now)

        assertEquals(5L, granted, "only 5 credits of headroom remain under the default cap")
        assertEquals(5L, userService.current(discordId, guildId)?.socialCredit)
        assertEquals(60L, dailyService.get(discordId, guildId, today)?.credits)
    }

    @Test
    fun `award with countsAgainstDailyCap false bypasses the cap and the ledger`() {
        userService.seed(UserDto(discordId, guildId).apply { socialCredit = 0L })
        dailyService.upsert(VoiceCreditDailyDto(discordId, guildId, today, credits = 60L)) // capped

        val granted = service.award(
            discordId, guildId,
            amount = 10L,
            reason = "test",
            countsAgainstDailyCap = false,
            at = now
        )

        assertEquals(10L, granted, "uncapped award should pass through the full amount")
        assertEquals(10L, userService.current(discordId, guildId)?.socialCredit)
        assertEquals(60L, dailyService.get(discordId, guildId, today)?.credits, "ledger untouched")
    }

    @Test
    fun `award returns zero and does not consume the cap when user row is missing`() {
        // No seed → unknown user.
        val granted = service.award(discordId, guildId, amount = 5L, reason = "test", at = now)

        assertEquals(0L, granted)
        assertEquals(0, userService.updateCount)
        assertEquals(null, dailyService.get(discordId, guildId, today), "ledger must not be charged for phantom award")
    }

    @Test
    fun `award with a custom dailyCap overrides the default`() {
        userService.seed(UserDto(discordId, guildId).apply { socialCredit = 0L })

        val granted = service.award(discordId, guildId, amount = 100L, reason = "test", at = now, dailyCap = 7L)

        assertEquals(7L, granted)
        assertEquals(7L, userService.current(discordId, guildId)?.socialCredit)
        assertEquals(7L, dailyService.get(discordId, guildId, today)?.credits)
    }

    private class RecordingUserService : UserService {
        private val users = mutableMapOf<Pair<Long, Long>, UserDto>()
        var updateCount: Int = 0
            private set

        fun seed(dto: UserDto) {
            users[dto.discordId to dto.guildId] = dto
        }

        fun current(discordId: Long, guildId: Long): UserDto? = users[discordId to guildId]

        override fun listGuildUsers(guildId: Long?): List<UserDto?> =
            users.values.filter { it.guildId == guildId }

        override fun createNewUser(userDto: UserDto): UserDto = userDto.also(::seed)

        override fun getUserById(discordId: Long?, guildId: Long?): UserDto? =
            users[discordId!! to guildId!!]

        override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? =
            getUserById(discordId, guildId)

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

    private class InMemoryVoiceCreditDailyService : VoiceCreditDailyService {
        private val rows = mutableMapOf<Triple<Long, Long, LocalDate>, VoiceCreditDailyDto>()

        override fun get(discordId: Long, guildId: Long, date: LocalDate): VoiceCreditDailyDto? =
            rows[Triple(discordId, guildId, date)]

        override fun upsert(row: VoiceCreditDailyDto): VoiceCreditDailyDto {
            rows[Triple(row.discordId, row.guildId, row.earnDate)] = row
            return row
        }
    }
}
