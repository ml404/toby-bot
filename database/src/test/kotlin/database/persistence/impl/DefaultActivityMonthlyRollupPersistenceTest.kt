package database.persistence.impl

import database.dto.ActivityMonthlyRollupDto
import database.dto.ActivityMonthlyRollupId
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import database.persistence.activity.impl.DefaultActivityMonthlyRollupPersistence

class DefaultActivityMonthlyRollupPersistenceTest {

    private lateinit var em: EntityManager
    private lateinit var persistence: DefaultActivityMonthlyRollupPersistence

    private val guildId = 1L
    private val discordId = 42L
    private val monthStart: LocalDate = LocalDate.of(2026, 5, 1)
    private val activityName = "voice"

    @BeforeEach
    fun setUp() {
        em = mockk(relaxed = true)
        persistence = DefaultActivityMonthlyRollupPersistence().apply {
            // Inject the mocked EntityManager into the lateinit field
            // populated by @PersistenceContext in production.
            DefaultActivityMonthlyRollupPersistence::class.java
                .getDeclaredField("entityManager")
                .apply { isAccessible = true }
                .set(this, em)
        }
    }

    @Test
    fun `addSeconds persists a fresh row when the composite key isn't present`() {
        every { em.find(ActivityMonthlyRollupDto::class.java, any()) } returns null

        val saved = persistence.addSeconds(discordId, guildId, monthStart, activityName, delta = 60L)

        assertEquals(discordId, saved.discordId)
        assertEquals(guildId, saved.guildId)
        assertEquals(monthStart, saved.monthStart)
        assertEquals(activityName, saved.activityName)
        assertEquals(60L, saved.seconds)
        verifyOrder {
            em.find(ActivityMonthlyRollupDto::class.java, any())
            em.persist(saved)
            em.flush()
        }
        verify(exactly = 0) { em.merge(any<ActivityMonthlyRollupDto>()) }
    }

    @Test
    fun `addSeconds accumulates onto the existing row when the composite key matches`() {
        val existing = ActivityMonthlyRollupDto(discordId, guildId, monthStart, activityName, seconds = 100L)
        every { em.find(ActivityMonthlyRollupDto::class.java, any()) } returns existing

        val result = persistence.addSeconds(discordId, guildId, monthStart, activityName, delta = 60L)

        assertSame(existing, result)
        assertEquals(160L, result.seconds)
        verifyOrder {
            em.find(ActivityMonthlyRollupDto::class.java, any())
            em.merge(existing)
            em.flush()
        }
        verify(exactly = 0) { em.persist(any<ActivityMonthlyRollupDto>()) }
    }

    @Test
    fun `addSeconds builds the composite id from all four key fields`() {
        val idSlot = slot<Any>()
        every { em.find(ActivityMonthlyRollupDto::class.java, capture(idSlot)) } returns null

        persistence.addSeconds(discordId, guildId, monthStart, activityName, delta = 5L)

        val id = idSlot.captured as ActivityMonthlyRollupId
        assertEquals(discordId, id.discordId)
        assertEquals(guildId, id.guildId)
        assertEquals(monthStart, id.monthStart)
        assertEquals(activityName, id.activityName)
    }

    @Test
    fun `forGuildMonth runs the named query with the two parameters and returns its rows`() {
        val rows = listOf(ActivityMonthlyRollupDto(discordId, guildId, monthStart, "voice", 100))
        val q = mockNamedQuery("ActivityMonthlyRollupDto.forGuildMonth", rows)

        val result = persistence.forGuildMonth(guildId, monthStart)

        assertSame(rows, result)
        verify {
            q.setParameter("guildId", guildId)
            q.setParameter("monthStart", monthStart)
        }
    }

    @Test
    fun `forUser runs the named query with discordId and guildId and returns its rows`() {
        val rows = listOf(ActivityMonthlyRollupDto(discordId, guildId, monthStart, "voice", 100))
        val q = mockNamedQuery("ActivityMonthlyRollupDto.forUser", rows)

        assertSame(rows, persistence.forUser(guildId, discordId))
        verify {
            q.setParameter("guildId", guildId)
            q.setParameter("discordId", discordId)
        }
    }

    @Test
    fun `forUserMonth binds all three keys and returns the rows`() {
        val rows = listOf(ActivityMonthlyRollupDto(discordId, guildId, monthStart, "voice", 100))
        val q = mockNamedQuery("ActivityMonthlyRollupDto.forUserMonth", rows)

        assertSame(rows, persistence.forUserMonth(guildId, discordId, monthStart))
        verify {
            q.setParameter("guildId", guildId)
            q.setParameter("discordId", discordId)
            q.setParameter("monthStart", monthStart)
        }
    }

    @Test
    fun `forGuildSince binds guildId and since and returns the rows`() {
        val since = LocalDate.of(2025, 6, 1)
        val rows = listOf(ActivityMonthlyRollupDto(discordId, guildId, monthStart, "voice", 100))
        val q = mockNamedQuery("ActivityMonthlyRollupDto.forGuildSince", rows)

        assertSame(rows, persistence.forGuildSince(guildId, since))
        verify {
            q.setParameter("guildId", guildId)
            q.setParameter("since", since)
        }
    }

    @Test
    fun `deleteBefore executes the named delete query with the cutoff and returns the row count`() {
        val cutoff = LocalDate.of(2025, 1, 1)
        val q = mockk<Query>(relaxed = true)
        every { em.createNamedQuery("ActivityMonthlyRollupDto.deleteBefore") } returns q
        every { q.setParameter("cutoff", cutoff) } returns q
        every { q.executeUpdate() } returns 7

        assertEquals(7, persistence.deleteBefore(cutoff))
        verify { q.setParameter("cutoff", cutoff) }
        verify { q.executeUpdate() }
    }

    private fun mockNamedQuery(
        name: String,
        rows: List<ActivityMonthlyRollupDto>,
    ): TypedQuery<ActivityMonthlyRollupDto> {
        val q = mockk<TypedQuery<ActivityMonthlyRollupDto>>(relaxed = true)
        every {
            em.createNamedQuery(name, ActivityMonthlyRollupDto::class.java)
        } returns q
        every { q.resultList } returns rows
        return q
    }
}
