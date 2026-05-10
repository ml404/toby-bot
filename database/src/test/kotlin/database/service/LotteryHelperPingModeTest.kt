package database.service

import database.dto.ConfigDto
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Read-time semantics for [LotteryHelper.lotteryPingMode]. Drives the
 * wide-ping prefix (`@everyone` / `@here` / nothing) on the daily
 * lottery announcement; an unknown / missing value must fall back to
 * the loudest mode so a fresh-install guild still pings everyone — the
 * whole point of the config is to dial that DOWN, not silently hide
 * the announcement.
 */
class LotteryHelperPingModeTest {

    private val guildId = 7L
    private val key = ConfigDto.Configurations.LOTTERY_PING_MODE

    private lateinit var configService: ConfigService

    @BeforeEach
    fun setup() {
        configService = mockk()
    }

    private fun stored(value: String?) {
        every { configService.getConfigByName(key.configValue, guildId.toString()) } returns
            value?.let { ConfigDto(name = key.configValue, value = it, guildId = guildId.toString()) }
    }

    @Test
    fun `defaults to EVERYONE when row is missing`() {
        stored(null)
        assertEquals(LotteryHelper.PING_EVERYONE, LotteryHelper.lotteryPingMode(configService, guildId))
    }

    @Test
    fun `falls back to EVERYONE on unknown value`() {
        stored("LOUD")
        assertEquals(LotteryHelper.PING_EVERYONE, LotteryHelper.lotteryPingMode(configService, guildId))
    }

    @Test
    fun `parses OFF`() {
        stored("OFF")
        assertEquals(LotteryHelper.PING_OFF, LotteryHelper.lotteryPingMode(configService, guildId))
    }

    @Test
    fun `parses HERE`() {
        stored("HERE")
        assertEquals(LotteryHelper.PING_HERE, LotteryHelper.lotteryPingMode(configService, guildId))
    }

    @Test
    fun `parses EVERYONE`() {
        stored("EVERYONE")
        assertEquals(LotteryHelper.PING_EVERYONE, LotteryHelper.lotteryPingMode(configService, guildId))
    }

    @Test
    fun `is case-insensitive`() {
        stored("here")
        assertEquals(LotteryHelper.PING_HERE, LotteryHelper.lotteryPingMode(configService, guildId))
    }

    @Test
    fun `trims whitespace`() {
        stored("  OFF  ")
        assertEquals(LotteryHelper.PING_OFF, LotteryHelper.lotteryPingMode(configService, guildId))
    }
}
