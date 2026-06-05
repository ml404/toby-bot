package bot.toby.scheduling

import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Coverage for [GuildHourGate.configuredHour]: returns the stored per-guild
 * UTC hour when valid, and falls back to the supplied default when the value
 * is absent, non-numeric, or out of the 0-23 range.
 */
class GuildHourGateTest {

    private val configService: ConfigService = mockk(relaxed = true)
    private val gate = GuildHourGate(configService)

    private val guildId = 100L
    private val key = ConfigDto.Configurations.STREAK_REMINDER_HOUR

    private fun stub(value: String?) {
        every {
            configService.getConfigByName(key.configValue, guildId.toString())
        } returns value?.let { ConfigDto(name = key.configValue, value = it, guildId = guildId.toString()) }
    }

    @Test
    fun `returns the default when no config is set`() {
        stub(null)
        assertEquals(18, gate.configuredHour(guildId, key, 18))
    }

    @Test
    fun `returns the configured hour when valid`() {
        stub("20")
        assertEquals(20, gate.configuredHour(guildId, key, 18))
    }

    @Test
    fun `accepts the range boundaries 0 and 23`() {
        stub("0")
        assertEquals(0, gate.configuredHour(guildId, key, 18))
        stub("23")
        assertEquals(23, gate.configuredHour(guildId, key, 18))
    }

    @Test
    fun `falls back to default when the value is out of range`() {
        stub("24")
        assertEquals(18, gate.configuredHour(guildId, key, 18))
        stub("-1")
        assertEquals(18, gate.configuredHour(guildId, key, 18))
    }

    @Test
    fun `falls back to default when the value is non-numeric or blank`() {
        stub("wibble")
        assertEquals(18, gate.configuredHour(guildId, key, 18))
        stub("  ")
        assertEquals(18, gate.configuredHour(guildId, key, 18))
    }

    @Test
    fun `trims surrounding whitespace before parsing`() {
        stub("  9  ")
        assertEquals(9, gate.configuredHour(guildId, key, 18))
    }
}
