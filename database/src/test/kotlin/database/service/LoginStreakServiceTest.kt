package database.service

import common.events.StreakClaimedEvent
import database.dto.ConfigDto
import database.dto.LoginStreakDto
import database.dto.UserDto
import database.dto.VoiceCreditDailyDto
import database.dto.XpDailyDto
import database.persistence.social.LoginStreakPersistence
import database.service.social.impl.DefaultLoginStreakService
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
import database.service.guild.ConfigService
import database.service.social.LoginStreakService
import database.service.social.SocialCreditAwardService
import database.service.user.UserService
import database.service.activity.VoiceCreditDailyService
import database.service.leveling.XpAwardService
import database.service.leveling.XpDailyService

class LoginStreakServiceTest {

    private val discordId = 1L
    private val guildId = 42L
    private val day1: Instant = Instant.parse("2026-04-10T10:00:00Z")
    private val day2: Instant = Instant.parse("2026-04-11T10:00:00Z")
    private val day3: Instant = Instant.parse("2026-04-12T10:00:00Z")
    private val gapDay: Instant = Instant.parse("2026-04-15T10:00:00Z")

    private lateinit var persistence: InMemoryLoginStreakPersistence
    private lateinit var userService: RecordingUserService
    private lateinit var xpDailyService: InMemoryXpDailyService
    private lateinit var voiceCreditDailyService: InMemoryVoiceCreditDailyService
    private lateinit var configService: InMemoryConfigService
    private lateinit var xpAwardService: XpAwardService
    private lateinit var socialCreditAwardService: SocialCreditAwardService
    private lateinit var eventPublisher: RecordingEventPublisher
    private lateinit var service: DefaultLoginStreakService

    @BeforeEach
    fun setup() {
        persistence = InMemoryLoginStreakPersistence()
        userService = RecordingUserService()
        userService.seed(UserDto(discordId, guildId).apply { xp = 0L; socialCredit = 0L })
        xpDailyService = InMemoryXpDailyService()
        voiceCreditDailyService = InMemoryVoiceCreditDailyService()
        configService = InMemoryConfigService()
        eventPublisher = RecordingEventPublisher()
        xpAwardService = XpAwardService(userService, xpDailyService, configService, eventPublisher)
        socialCreditAwardService = SocialCreditAwardService(userService, voiceCreditDailyService, configService)
        service = DefaultLoginStreakService(
            persistence = persistence,
            xpAwardService = xpAwardService,
            socialCreditAwardService = socialCreditAwardService,
            configService = configService,
            eventPublisher = eventPublisher
        )
    }

    @Test
    fun `first ever claim sets streak to one and publishes a StreakClaimedEvent`() {
        val result = service.claim(discordId, guildId, at = day1, channelId = 99L)

        assertTrue(result is LoginStreakService.ClaimResult.Granted)
        val granted = result as LoginStreakService.ClaimResult.Granted
        assertEquals(1, granted.currentStreak)
        assertEquals(1, granted.longestStreak)
        assertTrue(granted.isNewBest)
        assertTrue(granted.xpGranted > 0L)
        assertTrue(granted.creditsGranted > 0L)

        assertEquals(1, eventPublisher.streakEvents.size)
        val event = eventPublisher.streakEvents.single()
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
        assertEquals(1, event.currentStreak)
        assertEquals(99L, event.channelId)
    }

    @Test
    fun `same-day re-claim returns AlreadyClaimed and does not double-reward or re-fire the event`() {
        service.claim(discordId, guildId, at = day1)
        eventPublisher.streakEvents.clear()
        val xpBeforeSecondClaim = userService.current(discordId, guildId)?.xp

        val result = service.claim(discordId, guildId, at = day1.plusSeconds(60))

        assertTrue(result is LoginStreakService.ClaimResult.AlreadyClaimed)
        val already = result as LoginStreakService.ClaimResult.AlreadyClaimed
        assertEquals(1, already.currentStreak)
        assertEquals(xpBeforeSecondClaim, userService.current(discordId, guildId)?.xp)
        assertTrue(eventPublisher.streakEvents.isEmpty())
    }

    @Test
    fun `claiming on consecutive days extends the streak`() {
        service.claim(discordId, guildId, at = day1)
        service.claim(discordId, guildId, at = day2)
        val result = service.claim(discordId, guildId, at = day3)

        val granted = result as LoginStreakService.ClaimResult.Granted
        assertEquals(3, granted.currentStreak)
        assertEquals(3, granted.longestStreak)
        assertTrue(granted.isNewBest)
    }

    @Test
    fun `a missed day resets the streak to one but preserves longestStreak`() {
        service.claim(discordId, guildId, at = day1)
        service.claim(discordId, guildId, at = day2)
        // Skip day3 — gapDay is 3 days later.
        val result = service.claim(discordId, guildId, at = gapDay)

        val granted = result as LoginStreakService.ClaimResult.Granted
        assertEquals(1, granted.currentStreak, "streak resets after a gap")
        assertEquals(2, granted.longestStreak, "personal best is preserved")
        assertEquals(false, granted.isNewBest)
    }

    @Test
    fun `streak rewards bypass the daily XP cap`() {
        // Set DAILY_XP_CAP to a small value and exhaust it via direct ledger.
        configService.set(ConfigDto.Configurations.DAILY_XP_CAP.configValue, guildId.toString(), "5")
        val today = LocalDate.ofInstant(day1, java.time.ZoneOffset.UTC)
        xpDailyService.upsert(XpDailyDto(discordId, guildId, today, xpEarned = 5L))

        val result = service.claim(discordId, guildId, at = day1)

        val granted = result as LoginStreakService.ClaimResult.Granted
        assertTrue(granted.xpGranted > 0L, "streak XP must not be clamped by the daily cap")
        // The daily ledger should not have been updated by the streak award.
        assertEquals(5L, xpDailyService.get(discordId, guildId, today)?.xpEarned)
    }

    @Test
    fun `streak XP grows by per-day bonus up to the configured max`() {
        // Default: base 50, +5/day, max 300.
        service.claim(discordId, guildId, at = day1)
        val xpAfterDay1 = userService.current(discordId, guildId)?.xp ?: 0L
        service.claim(discordId, guildId, at = day2)
        val xpAfterDay2 = userService.current(discordId, guildId)?.xp ?: 0L

        val day1Reward = xpAfterDay1
        val day2Reward = xpAfterDay2 - xpAfterDay1
        assertEquals(50L, day1Reward, "day-1 reward is the base")
        assertEquals(55L, day2Reward, "day-2 reward is base + per-day bonus")
    }

    @Test
    fun `findActiveStreaksDueForReminder returns only at-risk active streaks`() {
        // User 1: active streak, claimed yesterday → at risk.
        service.claim(discordId = 1L, guildId = guildId, at = day1)
        // User 2: active streak, claimed today → safe.
        service.claim(discordId = 2L, guildId = guildId, at = day2)
        // User 3: never claimed → not at risk (no streak to lose).

        val today = LocalDate.ofInstant(day2, java.time.ZoneOffset.UTC)
        val due = service.findActiveStreaksDueForReminder(guildId, today)

        assertEquals(1, due.size)
        assertEquals(1L, due.single().discordId)
    }

    @Test
    fun `findActiveStreaksDueForReminder filters by guild`() {
        service.claim(discordId = 1L, guildId = guildId, at = day1)
        // Same user, different guild — also at risk in that guild.
        service.claim(discordId = 1L, guildId = 999L, at = day1)

        val today = LocalDate.ofInstant(day2, java.time.ZoneOffset.UTC)
        val dueA = service.findActiveStreaksDueForReminder(guildId, today)
        val dueB = service.findActiveStreaksDueForReminder(999L, today)

        assertEquals(1, dueA.size)
        assertEquals(1, dueB.size)
        assertEquals(guildId, dueA.single().guildId)
        assertEquals(999L, dueB.single().guildId)
    }

    @Test
    fun `claim updates totalClaims monotonically and persists last_claim_date`() {
        service.claim(discordId, guildId, at = day1)
        service.claim(discordId, guildId, at = day2)
        service.claim(discordId, guildId, at = day2.plusSeconds(60)) // same-day re-claim

        val row = persistence.get(discordId, guildId)
        assertNotNull(row)
        assertEquals(2L, row!!.totalClaims, "same-day re-claim must not bump total")
        assertEquals(LocalDate.ofInstant(day2, java.time.ZoneOffset.UTC), row.lastClaimDate)
    }

    // ---------- Fakes ----------

    private class InMemoryLoginStreakPersistence : LoginStreakPersistence {
        private val rows = mutableMapOf<Pair<Long, Long>, LoginStreakDto>()
        override fun get(discordId: Long, guildId: Long): LoginStreakDto? = rows[discordId to guildId]
        override fun upsert(row: LoginStreakDto): LoginStreakDto {
            rows[row.discordId to row.guildId] = row
            return row
        }
        override fun findActiveStreaksDueForReminder(
            guildId: Long,
            today: LocalDate
        ): List<LoginStreakDto> = rows.values.filter { row ->
            row.guildId == guildId && row.currentStreak > 0 &&
                (row.lastClaimDate == null || row.lastClaimDate!! < today)
        }
    }

    private class RecordingEventPublisher : ApplicationEventPublisher {
        val streakEvents: MutableList<StreakClaimedEvent> = mutableListOf()
        val otherEvents: MutableList<Any> = mutableListOf()
        override fun publishEvent(event: ApplicationEvent) {
            otherEvents.add(event)
        }
        override fun publishEvent(event: Any) {
            if (event is StreakClaimedEvent) streakEvents.add(event) else otherEvents.add(event)
        }
    }

    private class RecordingUserService : UserService {
        private val users = mutableMapOf<Pair<Long, Long>, UserDto>()
        fun seed(dto: UserDto) { users[dto.discordId to dto.guildId] = dto }
        fun current(discordId: Long, guildId: Long): UserDto? = users[discordId to guildId]
        override fun listGuildUsers(guildId: Long?): List<UserDto?> =
            users.values.filter { it.guildId == guildId }
        override fun createNewUser(userDto: UserDto): UserDto = userDto.also(::seed)
        override fun getUserById(discordId: Long?, guildId: Long?): UserDto? =
            users[discordId!! to guildId!!]
        override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? =
            getUserById(discordId, guildId)
        override fun updateUser(userDto: UserDto): UserDto = userDto.also(::seed)
        override fun deleteUser(userDto: UserDto) { users.remove(userDto.discordId to userDto.guildId) }
        override fun deleteUserById(discordId: Long?, guildId: Long?) {
            users.remove(discordId!! to guildId!!)
        }
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
        override fun upsertAll(
            guildId: String,
            entries: List<Pair<String, String>>,
        ): List<ConfigService.UpsertResult> =
            entries.map { (name, value) -> upsertConfig(name, value, guildId) }
    }
}
