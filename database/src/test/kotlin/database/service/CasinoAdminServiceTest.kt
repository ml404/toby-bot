package database.service

import database.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import database.service.casino.CasinoAdminService
import database.service.economy.JackpotService
import database.service.user.UserService

class CasinoAdminServiceTest {

    private val discordId = 100L
    private val guildId = 200L

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var service: CasinoAdminService

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        service = CasinoAdminService(userService, jackpotService)
    }

    @Test
    fun `refundToJackpot returns InvalidAmount for zero amount`() {
        val result = service.refundToJackpot(discordId, guildId, 0L)
        assertEquals(CasinoAdminService.RefundOutcome.InvalidAmount(0L), result)
    }

    @Test
    fun `refundToJackpot returns InvalidAmount for negative amount`() {
        val result = service.refundToJackpot(discordId, guildId, -5L)
        assertEquals(CasinoAdminService.RefundOutcome.InvalidAmount(-5L), result)
    }

    @Test
    fun `refundToJackpot returns Insufficient with have=0 when user record is missing`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val result = service.refundToJackpot(discordId, guildId, 100L)

        assertEquals(CasinoAdminService.RefundOutcome.Insufficient(have = 0L, needed = 100L), result)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
    }

    @Test
    fun `refundToJackpot returns Insufficient when balance is less than amount`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 50L }
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val result = service.refundToJackpot(discordId, guildId, 100L)

        assertEquals(CasinoAdminService.RefundOutcome.Insufficient(have = 50L, needed = 100L), result)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
    }

    @Test
    fun `refundToJackpot debits user and deposits to jackpot on success`() {
        val user = UserDto(discordId = discordId, guildId = guildId).apply { socialCredit = 500L }
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { jackpotService.addToPool(guildId, 200L) } returns 1_200L

        val result = service.refundToJackpot(discordId, guildId, 200L)

        assertEquals(
            CasinoAdminService.RefundOutcome.Ok(drained = 200L, newPool = 1_200L, newSourceBalance = 300L),
            result,
        )
        assertEquals(300L, user.socialCredit)
        verify(exactly = 1) { userService.updateUser(user) }
        verify(exactly = 1) { jackpotService.addToPool(guildId, 200L) }
    }

    @Test
    fun `resetJackpot delegates to JackpotService`() {
        every { jackpotService.resetPool(guildId) } returns 5_000L

        val drained = service.resetJackpot(guildId)

        assertEquals(5_000L, drained)
        verify(exactly = 1) { jackpotService.resetPool(guildId) }
    }
}
