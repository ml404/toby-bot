package database.service

import database.dto.ConfigDto
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
    private lateinit var configService: InMemoryConfigService
    private lateinit var service: SocialCreditAwardService

    @BeforeEach
    fun setup() {
        userService = RecordingUserService()
        dailyService = InMemoryVoiceCreditDailyService()
        configService = InMemoryConfigService()
        service = SocialCreditAwardService(userService, dailyService, configService)
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
        // 85/90 already used today.
        dailyService.upsert(VoiceCreditDailyDto(discordId, guildId, today, credits = 85L))

        val granted = service.award(discordId, guildId, amount = 20L, reason = "test", at = now)

        assertEquals(5L, granted, "only 5 credits of headroom remain under the default cap")
        assertEquals(5L, userService.current(discordId, guildId)?.socialCredit)
        assertEquals(90L, dailyService.get(discordId, guildId, today)?.credits)
    }

    @Test
    fun `award with countsAgainstDailyCap false bypasses the cap and the ledger`() {
        userService.seed(UserDto(discordId, guildId).apply { socialCredit = 0L })
        dailyService.upsert(VoiceCreditDailyDto(discordId, guildId, today, credits = 90L)) // capped

        val granted = service.award(
            discordId, guildId,
            amount = 10L,
            reason = "test",
            countsAgainstDailyCap = false,
            at = now
        )

        assertEquals(10L, granted, "uncapped award should pass through the full amount")
        assertEquals(10L, userService.current(discordId, guildId)?.socialCredit)
        assertEquals(90L, dailyService.get(discordId, guildId, today)?.credits, "ledger untouched")
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
    fun `award reads the per-guild DAILY_CREDIT_CAP override from config`() {
        userService.seed(UserDto(discordId, guildId).apply { socialCredit = 0L })
        configService.set(ConfigDto.Configurations.DAILY_CREDIT_CAP.configValue, guildId.toString(), "7")

        val granted = service.award(discordId, guildId, amount = 100L, reason = "test", at = now)

        assertEquals(7L, granted)
        assertEquals(7L, userService.current(discordId, guildId)?.socialCredit)
        assertEquals(7L, dailyService.get(discordId, guildId, today)?.credits)
    }

    @Test
    fun `award falls back to DEFAULT_DAILY_CAP when config value is unparseable`() {
        userService.seed(UserDto(discordId, guildId).apply { socialCredit = 0L })
        configService.set(ConfigDto.Configurations.DAILY_CREDIT_CAP.configValue, guildId.toString(), "not-a-number")

        val granted = service.award(discordId, guildId, amount = 200L, reason = "test", at = now)

        assertEquals(SocialCreditAwardService.DEFAULT_DAILY_CAP, granted)
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

    private class InMemoryConfigService : ConfigService {
        private val rows = mutableMapOf<Pair<String, String>, ConfigDto>()

        fun set(name: String, guildId: String, value: String) {
            rows[name to guildId] = ConfigDto(name = name, value = value, guildId = guildId)
        }

        override fun listAllConfig(): List<ConfigDto?>? = rows.values.toList()
        override fun listGuildConfig(guildId: String?): List<ConfigDto?>? =
            rows.values.filter { it.guildId == guildId }
        override fun getConfigByName(name: String?, guildId: String?): ConfigDto? =
            rows[name to guildId] ?: rows[(name ?: "") to "all"]
        override fun createNewConfig(configDto: ConfigDto): ConfigDto? = configDto.also {
            rows[(it.name ?: "") to (it.guildId ?: "")] = it
        }
        override fun updateConfig(configDto: ConfigDto?): ConfigDto? = configDto?.also {
            rows[(it.name ?: "") to (it.guildId ?: "")] = it
        }
        override fun deleteAll(guildId: String?) { rows.entries.removeIf { it.key.second == guildId } }
        override fun deleteConfig(guildId: String?, name: String?) { rows.remove((name ?: "") to (guildId ?: "")) }
        override fun clearCache() {}
        override fun upsertConfig(name: String, value: String, guildId: String): ConfigService.UpsertResult {
            val key = name to guildId
            val existing = rows[key]
            rows[key] = ConfigDto(name = name, value = value, guildId = guildId)
            return if (existing == null) {
                ConfigService.UpsertResult.Created(rows[key]!!)
            } else {
                ConfigService.UpsertResult.Updated(rows[key]!!, previousValue = existing.value)
            }
        }
    }
}
