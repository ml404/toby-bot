package database.service

import common.events.LevelUpEvent
import common.leveling.LevelCurve
import database.dto.ConfigDto
import database.dto.UserDto
import database.dto.XpDailyDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class XpAwardServiceTest {

    private val discordId = 1L
    private val guildId = 42L
    private val now: Instant = Instant.parse("2026-04-10T10:00:00Z")
    private val today: LocalDate = LocalDate.ofInstant(now, ZoneOffset.UTC)

    private lateinit var userService: RecordingUserService
    private lateinit var xpDailyService: InMemoryXpDailyService
    private lateinit var configService: InMemoryConfigService
    private lateinit var eventPublisher: RecordingEventPublisher
    private lateinit var service: XpAwardService

    @BeforeEach
    fun setup() {
        userService = RecordingUserService()
        xpDailyService = InMemoryXpDailyService()
        configService = InMemoryConfigService()
        eventPublisher = RecordingEventPublisher()
        service = XpAwardService(userService, xpDailyService, configService, eventPublisher)
    }

    @Test
    fun `award adds XP and persists via updateUser`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 10L })

        val granted = service.award(discordId, guildId, amount = 5L, reason = "test", at = now)

        assertEquals(5L, granted)
        assertEquals(15L, userService.current(discordId, guildId)?.xp)
        assertEquals(1, userService.updateCount)
    }

    @Test
    fun `award returns zero and no-ops for non-positive amounts`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 10L })

        assertEquals(0L, service.award(discordId, guildId, amount = 0L, reason = "test", at = now))
        assertEquals(0L, service.award(discordId, guildId, amount = -3L, reason = "test", at = now))

        assertEquals(10L, userService.current(discordId, guildId)?.xp)
        assertEquals(0, userService.updateCount)
    }

    @Test
    fun `award respects the daily cap and consumes the remaining headroom only`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 0L })
        // 995/1000 already used today.
        xpDailyService.upsert(XpDailyDto(discordId, guildId, today, xpEarned = 995L))

        val granted = service.award(discordId, guildId, amount = 20L, reason = "test", at = now)

        assertEquals(5L, granted, "only 5 XP of headroom remain under the default cap")
        assertEquals(5L, userService.current(discordId, guildId)?.xp)
        assertEquals(1000L, xpDailyService.get(discordId, guildId, today)?.xpEarned)
    }

    @Test
    fun `award with countsAgainstDailyCap false bypasses the cap and the ledger`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 0L })
        xpDailyService.upsert(XpDailyDto(discordId, guildId, today, xpEarned = 1000L)) // capped

        val granted = service.award(
            discordId, guildId,
            amount = 250L,
            reason = "test",
            countsAgainstDailyCap = false,
            at = now
        )

        assertEquals(250L, granted, "uncapped award should pass through the full amount")
        assertEquals(250L, userService.current(discordId, guildId)?.xp)
        assertEquals(1000L, xpDailyService.get(discordId, guildId, today)?.xpEarned, "ledger untouched")
    }

    @Test
    fun `award returns zero and does not consume the cap when user row is missing`() {
        val granted = service.award(discordId, guildId, amount = 5L, reason = "test", at = now)

        assertEquals(0L, granted)
        assertEquals(0, userService.updateCount)
        assertNull(xpDailyService.get(discordId, guildId, today))
    }

    @Test
    fun `award reads the per-guild DAILY_XP_CAP override from config`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 0L })
        configService.set(ConfigDto.Configurations.DAILY_XP_CAP.configValue, guildId.toString(), "7")

        val granted = service.award(discordId, guildId, amount = 100L, reason = "test", at = now)

        assertEquals(7L, granted)
        assertEquals(7L, userService.current(discordId, guildId)?.xp)
        assertEquals(7L, xpDailyService.get(discordId, guildId, today)?.xpEarned)
    }

    @Test
    fun `award falls back to DEFAULT_DAILY_XP_CAP when config value is unparseable`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 0L })
        configService.set(ConfigDto.Configurations.DAILY_XP_CAP.configValue, guildId.toString(), "junk")

        val granted = service.award(discordId, guildId, amount = 5000L, reason = "test", at = now)

        assertEquals(XpAwardService.DEFAULT_DAILY_XP_CAP, granted)
    }

    @Test
    fun `crossing a level threshold publishes a LevelUpEvent`() {
        // 99 XP -> level 0, add 1 -> 100 -> level 1.
        userService.seed(UserDto(discordId, guildId).apply { xp = 99L })

        service.award(
            discordId, guildId,
            amount = 1L,
            reason = "test",
            channelId = 1234L,
            countsAgainstDailyCap = false,
            at = now
        )

        assertEquals(1, eventPublisher.events.size)
        val event = eventPublisher.events.single() as LevelUpEvent
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
        assertEquals(0, event.oldLevel)
        assertEquals(1, event.newLevel)
        assertEquals(100L, event.totalXp)
        assertEquals(1234L, event.channelId)
    }

    @Test
    fun `staying within the same level does not publish a LevelUpEvent`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 100L }) // level 1

        service.award(
            discordId, guildId,
            amount = 50L,
            reason = "test",
            countsAgainstDailyCap = false,
            at = now
        )

        assertTrue(eventPublisher.events.isEmpty())
    }

    @Test
    fun `a single award can cross multiple levels at once`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 0L })
        val target = LevelCurve.cumulativeXpForLevel(3) // 475

        service.award(
            discordId, guildId,
            amount = target,
            reason = "test",
            countsAgainstDailyCap = false,
            at = now
        )

        assertEquals(1, eventPublisher.events.size)
        val event = eventPublisher.events.single() as LevelUpEvent
        assertEquals(0, event.oldLevel)
        assertEquals(3, event.newLevel)
    }

    @Test
    fun `daily cap blocking a level-up suppresses the event`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 0L })
        configService.set(ConfigDto.Configurations.DAILY_XP_CAP.configValue, guildId.toString(), "50")

        // Request enough to level up, but cap clamps it back below 100 XP.
        service.award(discordId, guildId, amount = 200L, reason = "test", at = now)

        assertEquals(50L, userService.current(discordId, guildId)?.xp)
        assertTrue(eventPublisher.events.isEmpty())
    }

    @Test
    fun `award returns zero when daily cap is already exhausted`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 0L })
        xpDailyService.upsert(XpDailyDto(discordId, guildId, today, xpEarned = 1000L))

        val granted = service.award(discordId, guildId, amount = 50L, reason = "test", at = now)

        assertEquals(0L, granted)
        assertEquals(0L, userService.current(discordId, guildId)?.xp)
    }

    @Test
    fun `event includes channelId when one is supplied`() {
        userService.seed(UserDto(discordId, guildId).apply { xp = 99L })

        service.award(
            discordId, guildId,
            amount = 200L,
            reason = "test",
            channelId = 7777L,
            countsAgainstDailyCap = false,
            at = now
        )

        assertNotNull(eventPublisher.events.firstOrNull())
        assertEquals(7777L, (eventPublisher.events.first() as LevelUpEvent).channelId)
    }

    private class RecordingEventPublisher : ApplicationEventPublisher {
        val events: MutableList<Any> = mutableListOf()
        override fun publishEvent(event: ApplicationEvent) {
            events.add(event)
        }
        override fun publishEvent(event: Any) {
            events.add(event)
        }
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

    private class InMemoryXpDailyService : XpDailyService {
        private val rows = mutableMapOf<Triple<Long, Long, LocalDate>, XpDailyDto>()

        override fun get(discordId: Long, guildId: Long, date: LocalDate): XpDailyDto? =
            rows[Triple(discordId, guildId, date)]

        override fun upsert(row: XpDailyDto): XpDailyDto {
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
