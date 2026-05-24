package database.service

import database.dto.guild.ConfigDto
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import database.service.guild.ConfigService
import database.service.guild.cfgLong
import database.service.guild.cfgLongMax

/**
 * Read-time semantics for [cfgLong] and [cfgLongMax]. The interesting case
 * for [cfgLongMax] is the `0 → Long.MAX_VALUE` shortcut admins use to mean
 * "no upper cap" on a `*_MAX_STAKE` / `POKER_MAX_BUY_IN` field; if that
 * coercion ever flips back to "0 reads as min" it silently caps every
 * minigame at the floor and the UI's "0 = unlimited" label becomes a lie.
 */
class ConfigReadersTest {

    private val guildId = 7L
    private val key = ConfigDto.Configurations.DICE_MAX_STAKE

    private lateinit var configService: ConfigService

    @BeforeEach
    fun setup() {
        configService = mockk()
    }

    private fun stored(value: String?) {
        every { configService.getConfigByName(key.configValue, guildId.toString()) } returns
            value?.let { ConfigDto(name = key.configValue, value = it, guildId = guildId.toString()) }
    }

    // ---- cfgLong ----

    @Test
    fun `cfgLong returns default when row missing`() {
        stored(null)
        assertEquals(500L, configService.cfgLong(key, guildId, default = 500L, min = 1L))
    }

    @Test
    fun `cfgLong returns default when value is unparseable`() {
        stored("not-a-number")
        assertEquals(500L, configService.cfgLong(key, guildId, default = 500L, min = 1L))
    }

    @Test
    fun `cfgLong coerces stored value below min up to min`() {
        stored("0")
        assertEquals(1L, configService.cfgLong(key, guildId, default = 500L, min = 1L))
    }

    @Test
    fun `cfgLong returns stored value when above min`() {
        stored("250")
        assertEquals(250L, configService.cfgLong(key, guildId, default = 500L, min = 1L))
    }

    // ---- cfgLongMax ----

    @Test
    fun `cfgLongMax expands stored 0 to Long_MAX_VALUE`() {
        stored("0")
        assertEquals(Long.MAX_VALUE, configService.cfgLongMax(key, guildId, default = 500L, min = 1L))
    }

    @Test
    fun `cfgLongMax returns default when row missing`() {
        stored(null)
        assertEquals(500L, configService.cfgLongMax(key, guildId, default = 500L, min = 1L))
    }

    @Test
    fun `cfgLongMax returns default when value is unparseable`() {
        stored("garbage")
        assertEquals(500L, configService.cfgLongMax(key, guildId, default = 500L, min = 1L))
    }

    @Test
    fun `cfgLongMax coerces a positive stored value below min up to min`() {
        // Defensive — write-time validation rejects negatives, but if a
        // legacy row has e.g. -5 we shouldn't return a negative max either.
        stored("-5")
        assertEquals(1L, configService.cfgLongMax(key, guildId, default = 500L, min = 1L))
    }

    @Test
    fun `cfgLongMax returns stored value when above min`() {
        stored("1000")
        assertEquals(1000L, configService.cfgLongMax(key, guildId, default = 500L, min = 1L))
    }
}
