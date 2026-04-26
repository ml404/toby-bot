package database.service

import database.dto.ConfigDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JackpotHelperTest {

    private val guildId = 200L

    private lateinit var jackpotService: JackpotService
    private lateinit var configService: ConfigService

    @BeforeEach
    fun setup() {
        jackpotService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
    }

    private fun configReturns(value: String?) {
        every {
            configService.getConfigByName(
                ConfigDto.Configurations.JACKPOT_LOSS_TRIBUTE_PCT.configValue,
                guildId.toString()
            )
        } returns value?.let { ConfigDto(name = "x", value = it, guildId = guildId.toString()) }
    }

    @Test
    fun `lossTributeRate returns the default fraction when no config row exists`() {
        configReturns(null)

        assertEquals(JackpotHelper.DEFAULT_LOSS_TRIBUTE, JackpotHelper.lossTributeRate(configService, guildId))
    }

    @Test
    fun `lossTributeRate parses whole-number percent into a fraction`() {
        configReturns("25")

        assertEquals(0.25, JackpotHelper.lossTributeRate(configService, guildId), 1e-9)
    }

    @Test
    fun `lossTributeRate clamps above MAX_LOSS_TRIBUTE`() {
        configReturns("99")

        assertEquals(JackpotHelper.MAX_LOSS_TRIBUTE, JackpotHelper.lossTributeRate(configService, guildId))
    }

    @Test
    fun `lossTributeRate falls back to default on unparseable value`() {
        configReturns("bogus")

        assertEquals(JackpotHelper.DEFAULT_LOSS_TRIBUTE, JackpotHelper.lossTributeRate(configService, guildId))
    }

    @Test
    fun `lossTributeRate accepts zero (admin disables tribute entirely)`() {
        configReturns("0")

        assertEquals(0.0, JackpotHelper.lossTributeRate(configService, guildId), 1e-9)
    }

    @Test
    fun `divertOnLoss deposits floor(stake * default rate) into the pool`() {
        configReturns(null)  // 10 % default

        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = 100L)

        assertEquals(10L, tribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    @Test
    fun `divertOnLoss honours the per-guild override`() {
        configReturns("25")  // 25 %

        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = 200L)

        assertEquals(50L, tribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 50L) }
    }

    @Test
    fun `divertOnLoss is a no-op when admin set rate to zero`() {
        configReturns("0")

        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = 1_000L)

        assertEquals(0L, tribute)
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
    }

    @Test
    fun `divertOnLoss is a no-op when the floor produces zero`() {
        configReturns(null)  // 10 % default → floor(9 * 0.10) = 0

        val tribute = JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = 9L)

        assertEquals(0L, tribute)
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
    }

    @Test
    fun `divertOnLoss ignores non-positive stakes`() {
        configReturns(null)

        assertEquals(0L, JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = 0L))
        assertEquals(0L, JackpotHelper.divertOnLoss(jackpotService, configService, guildId, stake = -10L))
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
    }
}
