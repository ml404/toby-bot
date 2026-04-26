package web.service

import database.dto.TipDailyDto
import database.dto.UserDto
import database.service.TipDailyService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TipWebServiceTest {
    private val sender = 1L
    private val recipient = 2L
    private val guildId = 42L
    private val today: LocalDate = LocalDate.parse("2026-04-10")

    private lateinit var tipDailyService: TipDailyService
    private lateinit var userService: UserService
    private lateinit var service: TipWebService

    @BeforeEach
    fun setup() {
        tipDailyService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        service = TipWebService(tipDailyService, userService)
    }

    @Test
    fun `getDailyTipped returns 0 when no row exists`() {
        every { tipDailyService.get(sender, guildId, today) } returns null

        assertEquals(0L, service.getDailyTipped(sender, guildId, today))
    }

    @Test
    fun `getDailyTipped returns row credits_sent`() {
        every { tipDailyService.get(sender, guildId, today) } returns
            TipDailyDto(sender, guildId, today, creditsSent = 175L)

        assertEquals(175L, service.getDailyTipped(sender, guildId, today))
    }

    @Test
    fun `ensureRecipient returns existing user without creating`() {
        val existing = UserDto(recipient, guildId)
        every { userService.getUserById(recipient, guildId) } returns existing

        val result = service.ensureRecipient(recipient, guildId)

        assertEquals(existing, result)
        verify(exactly = 0) { userService.createNewUser(any()) }
    }

    @Test
    fun `ensureRecipient lazily creates a row when none exists`() {
        every { userService.getUserById(recipient, guildId) } returns null
        val captured = slot()
        every { userService.createNewUser(capture(captured)) } answers { captured.captured }

        val result = service.ensureRecipient(recipient, guildId)

        assertEquals(recipient, result.discordId)
        assertEquals(guildId, result.guildId)
    }

    private inline fun <reified T : Any> slot(): io.mockk.CapturingSlot<T> = io.mockk.slot()
}
