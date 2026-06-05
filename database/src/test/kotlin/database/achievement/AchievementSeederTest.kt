package database.achievement

import database.dto.guild.AchievementDto
import database.dto.guild.AchievementProgressDto
import database.dto.guild.UserAchievementDto
import database.persistence.guild.AchievementPersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class AchievementSeederTest {

    private lateinit var persistence: InMemoryAchievementPersistence
    private val baseSpec = AchievementSpec(
        code = "test_a",
        name = "Test A",
        description = "first",
        category = "casino",
        icon = "🎲",
        xpReward = 25,
        creditReward = 10,
        threshold = 1
    )

    @BeforeEach
    fun setup() {
        persistence = InMemoryAchievementPersistence()
    }

    @Test
    fun `seed inserts missing rows on first run`() {
        val seeder = AchievementSeeder(persistence, specs = listOf(baseSpec))

        seeder.seed()

        val row = persistence.getByCode("test_a")
        assertNotNull(row)
        assertEquals("Test A", row!!.name)
        assertEquals(25, row.xpReward)
        assertEquals(10L, row.creditReward)
    }

    @Test
    fun `seed is idempotent - running twice does not create duplicate rows`() {
        val seeder = AchievementSeeder(persistence, specs = listOf(baseSpec))

        seeder.seed()
        seeder.seed()

        assertEquals(1, persistence.listAll().size)
    }

    @Test
    fun `seed updates display fields in place on subsequent runs`() {
        AchievementSeeder(persistence, specs = listOf(baseSpec)).seed()
        val originalId = persistence.getByCode("test_a")!!.id

        val tweaked = baseSpec.copy(
            name = "Test A (tuned)",
            description = "updated",
            icon = "🎰",
            xpReward = 100,
            creditReward = 50,
            threshold = 5
        )
        AchievementSeeder(persistence, specs = listOf(tweaked)).seed()

        val updated = persistence.getByCode("test_a")
        assertEquals(originalId, updated!!.id, "id must be stable across reseed")
        assertEquals("Test A (tuned)", updated.name)
        assertEquals("updated", updated.description)
        assertEquals("🎰", updated.icon)
        assertEquals(100, updated.xpReward)
        assertEquals(50L, updated.creditReward)
        assertEquals(5L, updated.threshold)
    }

    @Test
    fun `seed handles a multi-entry catalog without losing rows`() {
        val specs = listOf(
            baseSpec,
            baseSpec.copy(code = "test_b", name = "B"),
            baseSpec.copy(code = "test_c", name = "C", hidden = true)
        )

        AchievementSeeder(persistence, specs = specs).seed()

        assertEquals(3, persistence.listAll().size)
        assertNotNull(persistence.getByCode("test_a"))
        assertNotNull(persistence.getByCode("test_b"))
        assertNotNull(persistence.getByCode("test_c"))
    }

    // ---------- Fake ----------

    private class InMemoryAchievementPersistence : AchievementPersistence {
        private val byId = mutableMapOf<Long, AchievementDto>()
        private val byCode = mutableMapOf<String, AchievementDto>()
        private var nextId: Long = 1L

        override fun listAll(): List<AchievementDto> = byId.values.toList()
        override fun getByCode(code: String): AchievementDto? = byCode[code]
        override fun getById(id: Long): AchievementDto? = byId[id]
        override fun save(achievement: AchievementDto): AchievementDto {
            if (achievement.id == null) achievement.id = nextId++
            byId[achievement.id!!] = achievement
            byCode[achievement.code] = achievement
            achievement.createdAt = achievement.createdAt.takeIf { it.epochSecond > 0 } ?: Instant.now()
            return achievement
        }

        override fun listOwnedByUser(discordId: Long, guildId: Long): List<UserAchievementDto> = emptyList()
        override fun owns(discordId: Long, guildId: Long, achievementId: Long): Boolean = false
        override fun recordUnlock(unlock: UserAchievementDto): UserAchievementDto = unlock
        override fun getProgress(discordId: Long, guildId: Long, achievementId: Long): AchievementProgressDto? = null
        override fun listProgressByUser(discordId: Long, guildId: Long): List<AchievementProgressDto> = emptyList()
        override fun upsertProgress(row: AchievementProgressDto): AchievementProgressDto = row
        override fun progressByCodesForGuild(
            guildId: Long,
            codes: Collection<String>,
        ): List<database.persistence.guild.ProgressByCodeRow> = emptyList()
    }
}
