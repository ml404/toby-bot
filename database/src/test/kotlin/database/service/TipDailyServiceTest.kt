package database.service

import database.dto.TipDailyDto
import database.persistence.TipDailyPersistence
import database.service.impl.DefaultTipDailyService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TipDailyServiceTest {

    private val sender = 1L
    private val guildId = 42L
    private val today: LocalDate = LocalDate.parse("2026-04-10")

    private lateinit var persistence: TipDailyPersistence
    private lateinit var service: TipDailyService

    @BeforeEach
    fun setup() {
        persistence = mockk(relaxed = true)
        service = DefaultTipDailyService(persistence)
    }

    @Test
    fun `get delegates to persistence`() {
        val row = TipDailyDto(sender, guildId, today, creditsSent = 75L)
        every { persistence.get(sender, guildId, today) } returns row

        assertEquals(row, service.get(sender, guildId, today))
        verify(exactly = 1) { persistence.get(sender, guildId, today) }
    }

    @Test
    fun `get returns null when persistence has no row`() {
        every { persistence.get(sender, guildId, today) } returns null

        assertNull(service.get(sender, guildId, today))
    }

    @Test
    fun `upsert delegates to persistence and returns its row`() {
        val row = TipDailyDto(sender, guildId, today, creditsSent = 100L)
        every { persistence.upsert(row) } returns row

        assertEquals(row, service.upsert(row))
        verify(exactly = 1) { persistence.upsert(row) }
    }
}
