package database.persistence.social.impl

import database.dto.social.LoginStreakDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import jakarta.persistence.TypedQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class DefaultLoginStreakPersistenceTest {

    private val em: EntityManager = mockk(relaxed = true)
    private val query: TypedQuery<LoginStreakDto> = mockk(relaxed = true)
    private val persistence = DefaultLoginStreakPersistence().apply {
        DefaultLoginStreakPersistence::class.java
            .getDeclaredField("entityManager")
            .apply { isAccessible = true }
            .set(this, em)
    }

    @Test
    fun `findActiveStreaksDueForReminder only asks for rows last claimed yesterday`() {
        val today = LocalDate.of(2026, 11, 25)
        val row = LoginStreakDto(discordId = 1L, guildId = 2L, currentStreak = 4, lastClaimDate = today.minusDays(1))
        every {
            em.createNamedQuery("LoginStreakDto.findActiveStreaksDueForReminder", LoginStreakDto::class.java)
        } returns query
        every { query.resultList } returns listOf(row)

        val result = persistence.findActiveStreaksDueForReminder(guildId = 2L, today = today)

        assertEquals(listOf(row), result)
        verify { query.setParameter("guildId", 2L) }
        verify { query.setParameter("atRiskClaimDate", LocalDate.of(2026, 11, 24)) }
    }
}
