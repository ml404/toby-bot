package database.service.impl

import database.dto.activity.ActivityMonthlyRollupDto
import database.persistence.activity.ActivityMonthlyRollupPersistence
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import database.service.activity.impl.DefaultActivityMonthlyRollupService

class DefaultActivityMonthlyRollupServiceTest {

    private lateinit var persistence: ActivityMonthlyRollupPersistence
    private lateinit var service: DefaultActivityMonthlyRollupService

    private val guildId = 1L
    private val discordId = 42L
    private val monthStart: LocalDate = LocalDate.of(2026, 5, 1)
    private val activityName = "voice"

    @BeforeEach
    fun setUp() {
        persistence = mockk(relaxed = false)
        service = DefaultActivityMonthlyRollupService(persistence)
    }

    @Test
    fun `addSeconds delegates with all arguments and returns the persisted row`() {
        val row = ActivityMonthlyRollupDto(discordId, guildId, monthStart, activityName, seconds = 60)
        every { persistence.addSeconds(discordId, guildId, monthStart, activityName, 60) } returns row

        val result = service.addSeconds(discordId, guildId, monthStart, activityName, 60)

        assertSame(row, result)
        verify(exactly = 1) { persistence.addSeconds(discordId, guildId, monthStart, activityName, 60) }
    }

    @Test
    fun `forGuildMonth delegates and returns the persistence list`() {
        val rows = listOf(
            ActivityMonthlyRollupDto(discordId, guildId, monthStart, "voice", 100),
            ActivityMonthlyRollupDto(discordId, guildId, monthStart, "messages", 50),
        )
        every { persistence.forGuildMonth(guildId, monthStart) } returns rows

        assertEquals(rows, service.forGuildMonth(guildId, monthStart))
        verify(exactly = 1) { persistence.forGuildMonth(guildId, monthStart) }
    }

    @Test
    fun `forUser delegates and returns the persistence list`() {
        val rows = listOf(ActivityMonthlyRollupDto(discordId, guildId, monthStart, "voice", 100))
        every { persistence.forUser(guildId, discordId) } returns rows

        assertEquals(rows, service.forUser(guildId, discordId))
        verify(exactly = 1) { persistence.forUser(guildId, discordId) }
    }

    @Test
    fun `forUserMonth delegates with all three keys and returns the list`() {
        val rows = listOf(ActivityMonthlyRollupDto(discordId, guildId, monthStart, "voice", 100))
        every { persistence.forUserMonth(guildId, discordId, monthStart) } returns rows

        assertEquals(rows, service.forUserMonth(guildId, discordId, monthStart))
        verify(exactly = 1) { persistence.forUserMonth(guildId, discordId, monthStart) }
    }

    @Test
    fun `forGuildSince delegates and returns the list (cache annotation is Spring proxy behavior)`() {
        // The @Cacheable annotation only fires through a Spring proxy; the
        // direct unit call here exercises the method body, which is pure
        // delegation. Cache hits/misses are framework behavior tested
        // separately by Spring's own machinery.
        val since = LocalDate.of(2025, 6, 1)
        val rows = listOf(ActivityMonthlyRollupDto(discordId, guildId, monthStart, "voice", 100))
        every { persistence.forGuildSince(guildId, since) } returns rows

        assertEquals(rows, service.forGuildSince(guildId, since))
        verify(exactly = 1) { persistence.forGuildSince(guildId, since) }
    }

    @Test
    fun `deleteBefore delegates the cutoff and returns the affected-row count`() {
        val cutoff = LocalDate.of(2025, 1, 1)
        every { persistence.deleteBefore(cutoff) } returns 7

        assertEquals(7, service.deleteBefore(cutoff))
        verify(exactly = 1) { persistence.deleteBefore(cutoff) }
    }

    @Test
    fun `empty results pass through unchanged for read methods`() {
        every { persistence.forGuildMonth(guildId, monthStart) } returns emptyList()
        every { persistence.forUser(guildId, discordId) } returns emptyList()
        every { persistence.forUserMonth(guildId, discordId, monthStart) } returns emptyList()
        every { persistence.forGuildSince(guildId, monthStart) } returns emptyList()

        assertEquals(emptyList<ActivityMonthlyRollupDto>(), service.forGuildMonth(guildId, monthStart))
        assertEquals(emptyList<ActivityMonthlyRollupDto>(), service.forUser(guildId, discordId))
        assertEquals(emptyList<ActivityMonthlyRollupDto>(), service.forUserMonth(guildId, discordId, monthStart))
        assertEquals(emptyList<ActivityMonthlyRollupDto>(), service.forGuildSince(guildId, monthStart))
    }
}
