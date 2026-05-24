package database.service

import common.events.user.AchievementUnlockedEvent
import database.dto.guild.AchievementDto
import database.dto.guild.AchievementProgressDto
import database.dto.guild.ConfigDto
import database.dto.guild.UserAchievementDto
import database.dto.user.UserDto
import database.dto.activity.VoiceCreditDailyDto
import database.dto.leveling.XpDailyDto
import database.persistence.guild.AchievementPersistence
import database.service.guild.impl.DefaultAchievementService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import database.service.guild.ConfigService
import database.service.social.SocialCreditAwardService
import database.service.user.UserService
import database.service.activity.VoiceCreditDailyService
import database.service.leveling.XpAwardService
import database.service.leveling.XpDailyService

class AchievementServiceTest {

    private val discordId = 1L
    private val guildId = 42L

    private lateinit var persistence: InMemoryAchievementPersistence
    private lateinit var userService: RecordingUserService
    private lateinit var xpDailyService: InMemoryXpDailyService
    private lateinit var voiceCreditDailyService: InMemoryVoiceCreditDailyService
    private lateinit var configService: InMemoryConfigService
    private lateinit var xpAwardService: XpAwardService
    private lateinit var socialCreditAwardService: SocialCreditAwardService
    private lateinit var eventPublisher: RecordingEventPublisher
    private lateinit var service: DefaultAchievementService

    @BeforeEach
    fun setup() {
        persistence = InMemoryAchievementPersistence()
        userService = RecordingUserService()
        userService.seed(UserDto(discordId, guildId).apply { xp = 0L; socialCredit = 0L })
        xpDailyService = InMemoryXpDailyService()
        voiceCreditDailyService = InMemoryVoiceCreditDailyService()
        configService = InMemoryConfigService()
        eventPublisher = RecordingEventPublisher()
        xpAwardService = XpAwardService(userService, xpDailyService, configService, eventPublisher)
        socialCreditAwardService = SocialCreditAwardService(userService, voiceCreditDailyService, configService)
        service = DefaultAchievementService(persistence, xpAwardService, socialCreditAwardService, eventPublisher)
    }

    @Test
    fun `unlock awards XP and credits and publishes an event exactly once`() {
        val a = persistence.save(
            AchievementDto(code = "test_a", name = "Test", description = "d", category = "c", xpReward = 25, creditReward = 50)
        )

        val result = service.unlock(discordId, guildId, "test_a")

        assertTrue(result.unlocked)
        assertFalse(result.alreadyUnlocked)
        assertEquals(1, eventPublisher.unlockEvents.size)
        assertEquals("test_a", eventPublisher.unlockEvents.single().achievementCode)
        assertEquals(25L, userService.current(discordId, guildId)?.xp)
        assertEquals(50L, userService.current(discordId, guildId)?.socialCredit)
        assertTrue(persistence.owns(discordId, guildId, a.id!!))
    }

    @Test
    fun `unlock is idempotent — second call no-ops and does not double-award`() {
        persistence.save(AchievementDto(code = "test_b", name = "B", description = "d", category = "c", xpReward = 10, creditReward = 0))

        service.unlock(discordId, guildId, "test_b")
        val xpAfterFirst = userService.current(discordId, guildId)?.xp
        eventPublisher.unlockEvents.clear()

        val again = service.unlock(discordId, guildId, "test_b")

        assertFalse(again.unlocked)
        assertTrue(again.alreadyUnlocked)
        assertEquals(xpAfterFirst, userService.current(discordId, guildId)?.xp)
        assertTrue(eventPublisher.unlockEvents.isEmpty())
    }

    @Test
    fun `progress increments the counter without unlocking until the threshold is crossed`() {
        persistence.save(AchievementDto(code = "test_c", name = "C", description = "d", category = "c", xpReward = 100, creditReward = 0, threshold = 3))

        val r1 = service.progress(discordId, guildId, "test_c", delta = 1L)
        assertFalse(r1.unlocked)
        assertEquals(1L, r1.newProgress)

        val r2 = service.progress(discordId, guildId, "test_c", delta = 1L)
        assertFalse(r2.unlocked)
        assertEquals(2L, r2.newProgress)
        assertTrue(eventPublisher.unlockEvents.isEmpty())

        val r3 = service.progress(discordId, guildId, "test_c", delta = 1L)
        assertTrue(r3.unlocked)
        assertEquals(1, eventPublisher.unlockEvents.size)
        assertEquals(100L, userService.current(discordId, guildId)?.xp)
    }

    @Test
    fun `progress with delta exceeding the threshold still only unlocks once`() {
        persistence.save(AchievementDto(code = "big_jump", name = "Big", description = "d", category = "c", xpReward = 50, threshold = 5))

        val result = service.progress(discordId, guildId, "big_jump", delta = 100L)

        assertTrue(result.unlocked)
        assertEquals(1, eventPublisher.unlockEvents.size)

        // Further progress on the same achievement is a no-op.
        val followUp = service.progress(discordId, guildId, "big_jump", delta = 10L)
        assertFalse(followUp.unlocked)
        assertTrue(followUp.alreadyUnlocked)
        assertEquals(1, eventPublisher.unlockEvents.size)
    }

    @Test
    fun `progress on an unknown code is a no-op`() {
        val result = service.progress(discordId, guildId, "no_such_code", delta = 1L)
        assertFalse(result.unlocked)
        assertNull(result.achievement)
        assertTrue(eventPublisher.unlockEvents.isEmpty())
    }

    @Test
    fun `listFor returns unlocked entries with unlockedAt populated and locked entries with progress`() {
        val unlocked = persistence.save(AchievementDto(code = "u", name = "U", description = "u", category = "c", threshold = 1))
        val inProgress = persistence.save(AchievementDto(code = "p", name = "P", description = "p", category = "c", threshold = 10))
        service.unlock(discordId, guildId, "u")
        service.progress(discordId, guildId, "p", delta = 4L)

        val views = service.listFor(discordId, guildId).associateBy { it.achievement.code }

        assertNotNull(views["u"]?.unlockedAt)
        assertNull(views["p"]?.unlockedAt)
        assertEquals(4L, views["p"]?.progress)
    }

    // ---------- setProgress ----------

    @Test
    fun `setProgress sets absolute value and does not unlock when below threshold`() {
        val a = persistence.save(
            AchievementDto(code = "sp_below", name = "B", description = "d", category = "c", xpReward = 10, threshold = 10)
        )

        val result = service.setProgress(discordId, guildId, "sp_below", value = 4L)

        assertFalse(result.unlocked)
        assertEquals(4L, result.newProgress)
        assertEquals(4L, persistence.getProgress(discordId, guildId, a.id!!)?.progress)
        assertTrue(eventPublisher.unlockEvents.isEmpty())
        assertFalse(persistence.owns(discordId, guildId, a.id!!))
    }

    @Test
    fun `setProgress unlocks exactly once when value reaches the threshold`() {
        val a = persistence.save(
            AchievementDto(code = "sp_reach", name = "R", description = "d", category = "c", xpReward = 25, creditReward = 50, threshold = 5)
        )

        val result = service.setProgress(discordId, guildId, "sp_reach", value = 5L)

        assertTrue(result.unlocked)
        assertFalse(result.alreadyUnlocked)
        assertEquals(5L, result.newProgress)
        assertEquals(1, eventPublisher.unlockEvents.size)
        assertEquals(25L, userService.current(discordId, guildId)?.xp)
        assertEquals(50L, userService.current(discordId, guildId)?.socialCredit)
        assertTrue(persistence.owns(discordId, guildId, a.id!!))
    }

    @Test
    fun `setProgress clamps values above the threshold`() {
        val a = persistence.save(
            AchievementDto(code = "sp_clamp_high", name = "H", description = "d", category = "c", xpReward = 10, threshold = 5)
        )

        val result = service.setProgress(discordId, guildId, "sp_clamp_high", value = 99L)

        assertTrue(result.unlocked)
        assertEquals(5L, result.newProgress)
        assertEquals(1, eventPublisher.unlockEvents.size)
        assertEquals(10L, userService.current(discordId, guildId)?.xp)
        assertTrue(persistence.owns(discordId, guildId, a.id!!))
    }

    @Test
    fun `setProgress is a no-op once unlocked — streak-broke does not retract`() {
        persistence.save(
            AchievementDto(code = "sp_idem", name = "I", description = "d", category = "c", xpReward = 10, threshold = 3)
        )
        service.setProgress(discordId, guildId, "sp_idem", value = 3L)
        val xpAfterFirst = userService.current(discordId, guildId)?.xp
        eventPublisher.unlockEvents.clear()

        val again = service.setProgress(discordId, guildId, "sp_idem", value = 1L)

        assertFalse(again.unlocked)
        assertTrue(again.alreadyUnlocked)
        assertEquals(xpAfterFirst, userService.current(discordId, guildId)?.xp)
        assertTrue(eventPublisher.unlockEvents.isEmpty())
    }

    @Test
    fun `setProgress accepts a decrease for not-yet-unlocked rows`() {
        val a = persistence.save(
            AchievementDto(code = "sp_dec", name = "D", description = "d", category = "c", xpReward = 0, threshold = 10)
        )

        service.setProgress(discordId, guildId, "sp_dec", value = 7L)
        assertEquals(7L, persistence.getProgress(discordId, guildId, a.id!!)?.progress)

        val after = service.setProgress(discordId, guildId, "sp_dec", value = 3L)
        assertFalse(after.unlocked)
        assertEquals(3L, after.newProgress)
        assertEquals(3L, persistence.getProgress(discordId, guildId, a.id!!)?.progress)
    }

    @Test
    fun `setProgress clamps negative values to zero`() {
        val a = persistence.save(
            AchievementDto(code = "sp_neg", name = "N", description = "d", category = "c", threshold = 5)
        )

        val result = service.setProgress(discordId, guildId, "sp_neg", value = -5L)

        assertFalse(result.unlocked)
        assertEquals(0L, result.newProgress)
        assertEquals(0L, persistence.getProgress(discordId, guildId, a.id!!)?.progress)
    }

    @Test
    fun `setProgress on an unknown code is a no-op`() {
        val result = service.setProgress(discordId, guildId, "no_such_code", value = 5L)
        assertFalse(result.unlocked)
        assertNull(result.achievement)
        assertTrue(eventPublisher.unlockEvents.isEmpty())
    }

    @Test
    fun `setProgress publishes the same AchievementUnlockedEvent payload as unlock`() {
        persistence.save(
            AchievementDto(
                code = "sp_event", name = "Event", description = "desc",
                category = "c", icon = "🎯", xpReward = 10, threshold = 2
            )
        )

        service.setProgress(discordId, guildId, "sp_event", value = 2L, channelId = 555L)

        val event = eventPublisher.unlockEvents.single()
        assertEquals("sp_event", event.achievementCode)
        assertEquals("Event", event.name)
        assertEquals("desc", event.description)
        assertEquals("🎯", event.icon)
        assertEquals(555L, event.channelId)
        assertEquals(discordId, event.discordId)
        assertEquals(guildId, event.guildId)
    }

    @Test
    fun `streak rewards bypass the daily cap via countsAgainstDailyCap=false`() {
        // Exhaust the user's daily XP cap.
        configService.set(ConfigDto.Configurations.DAILY_XP_CAP.configValue, guildId.toString(), "10")
        xpDailyService.upsert(XpDailyDto(discordId, guildId, LocalDate.now(), xpEarned = 10L))

        persistence.save(AchievementDto(code = "bypass", name = "B", description = "d", category = "c", xpReward = 75, creditReward = 0))
        service.unlock(discordId, guildId, "bypass")

        // Reward landed fully — cap exhaustion did not eat it.
        assertEquals(75L, userService.current(discordId, guildId)?.xp)
    }

    // ---------- Fakes ----------

    private class InMemoryAchievementPersistence : AchievementPersistence {
        private val catalogue = mutableMapOf<Long, AchievementDto>()
        private val byCode = mutableMapOf<String, AchievementDto>()
        private val unlocks = mutableMapOf<Triple<Long, Long, Long>, UserAchievementDto>()
        private val progress = mutableMapOf<Triple<Long, Long, Long>, AchievementProgressDto>()
        private var nextId: Long = 1L

        override fun listAll(): List<AchievementDto> = catalogue.values.toList()
        override fun getByCode(code: String): AchievementDto? = byCode[code]
        override fun getById(id: Long): AchievementDto? = catalogue[id]
        override fun save(achievement: AchievementDto): AchievementDto {
            if (achievement.id == null) achievement.id = nextId++
            catalogue[achievement.id!!] = achievement
            byCode[achievement.code] = achievement
            return achievement
        }

        override fun listOwnedByUser(discordId: Long, guildId: Long): List<UserAchievementDto> =
            unlocks.values.filter { it.discordId == discordId && it.guildId == guildId }
        override fun owns(discordId: Long, guildId: Long, achievementId: Long): Boolean =
            unlocks.containsKey(Triple(discordId, guildId, achievementId))
        override fun recordUnlock(unlock: UserAchievementDto): UserAchievementDto {
            unlocks[Triple(unlock.discordId, unlock.guildId, unlock.achievementId)] = unlock
            return unlock
        }

        override fun getProgress(discordId: Long, guildId: Long, achievementId: Long): AchievementProgressDto? =
            progress[Triple(discordId, guildId, achievementId)]
        override fun listProgressByUser(discordId: Long, guildId: Long): List<AchievementProgressDto> =
            progress.values.filter { it.discordId == discordId && it.guildId == guildId }
        override fun upsertProgress(row: AchievementProgressDto): AchievementProgressDto {
            progress[Triple(row.discordId, row.guildId, row.achievementId)] = row
            return row
        }
        override fun progressByCodesForGuild(
            guildId: Long,
            codes: Collection<String>,
        ): List<database.persistence.guild.ProgressByCodeRow> {
            val codesSet = codes.toSet()
            return progress.values.asSequence()
                .filter { it.guildId == guildId }
                .mapNotNull { p ->
                    val code = catalogue[p.achievementId]?.code ?: return@mapNotNull null
                    if (code !in codesSet || p.progress <= 0L) return@mapNotNull null
                    database.persistence.guild.ProgressByCodeRow(p.discordId, code, p.progress)
                }
                .toList()
        }
    }

    private class RecordingEventPublisher : ApplicationEventPublisher {
        val unlockEvents: MutableList<AchievementUnlockedEvent> = mutableListOf()
        val otherEvents: MutableList<Any> = mutableListOf()
        override fun publishEvent(event: ApplicationEvent) { otherEvents.add(event) }
        override fun publishEvent(event: Any) {
            if (event is AchievementUnlockedEvent) unlockEvents.add(event) else otherEvents.add(event)
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
