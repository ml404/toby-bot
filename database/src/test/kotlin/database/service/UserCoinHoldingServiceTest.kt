package database.service

import common.economy.Coin
import database.dto.economy.UserCoinHoldingDto
import database.persistence.economy.UserCoinHoldingPersistence
import database.service.economy.impl.DefaultUserCoinHoldingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class UserCoinHoldingServiceTest {

    private val persistence: UserCoinHoldingPersistence = mockk(relaxed = true)
    private val service = DefaultUserCoinHoldingService(persistence)

    @Test
    fun `getAmount delegates straight to persistence`() {
        every { persistence.getAmount(1L, 2L, Coin.MOON) } returns 42L
        assertEquals(42L, service.getAmount(1L, 2L, Coin.MOON))
        verify(exactly = 1) { persistence.getAmount(1L, 2L, Coin.MOON) }
    }

    @Test
    fun `listForUser delegates straight to persistence`() {
        val rows = listOf(UserCoinHoldingDto(1L, 2L, Coin.RUFF.symbol, 9L))
        every { persistence.listForUser(1L, 2L) } returns rows
        assertEquals(rows, service.listForUser(1L, 2L))
        verify(exactly = 1) { persistence.listForUser(1L, 2L) }
    }
}
