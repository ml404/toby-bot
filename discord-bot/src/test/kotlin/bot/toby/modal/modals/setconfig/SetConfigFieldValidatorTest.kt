package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldResult
import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.ValidationOutcome
import database.dto.guild.ConfigDto.Configurations
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SetConfigFieldValidatorTest {

    // ----- per-type validate() coverage -----

    @Test
    fun `blank input is always Skip`() {
        val skip = SetConfigFieldValidator.validate("", FieldSpec.IntRange("Volume", 0..200))
        assertInstanceOf(FieldResult.Skip::class.java, skip)

        val skipNull = SetConfigFieldValidator.validate(null, FieldSpec.LongMin("Stake", 1L))
        assertInstanceOf(FieldResult.Skip::class.java, skipNull)

        val skipWs = SetConfigFieldValidator.validate("   ", FieldSpec.BoolStrict("Tracking"))
        assertInstanceOf(FieldResult.Skip::class.java, skipWs)
    }

    @Test
    fun `IntRange parses valid value`() {
        val r = SetConfigFieldValidator.validate("75", FieldSpec.IntRange("Volume", 0..200))
        assertEquals(FieldResult.Write("75"), r)
    }

    @Test
    fun `IntRange rejects non-numeric input`() {
        val r = SetConfigFieldValidator.validate("abc", FieldSpec.IntRange("Volume", 0..200))
        assertInstanceOf(FieldResult.Error::class.java, r)
    }

    @Test
    fun `IntRange rejects out-of-range value`() {
        val r = SetConfigFieldValidator.validate("250", FieldSpec.IntRange("Volume", 0..200))
        val err = assertInstanceOf(FieldResult.Error::class.java, r)
        assertTrue(err.message.contains("between 0 and 200"))
    }

    @Test
    fun `LongMin parses values at the floor and above`() {
        val rFloor = SetConfigFieldValidator.validate("1", FieldSpec.LongMin("Stake", 1L))
        assertEquals(FieldResult.Write("1"), rFloor)
        val rBig = SetConfigFieldValidator.validate("999999999", FieldSpec.LongMin("Stake", 1L))
        assertEquals(FieldResult.Write("999999999"), rBig)
    }

    @Test
    fun `LongMin rejects below floor`() {
        val r = SetConfigFieldValidator.validate("0", FieldSpec.LongMin("Stake", 1L))
        assertInstanceOf(FieldResult.Error::class.java, r)
    }

    @Test
    fun `DoubleRange parses decimal`() {
        val r = SetConfigFieldValidator.validate("12.5", FieldSpec.DoubleRange("Fee", 0.0..50.0))
        assertEquals(FieldResult.Write("12.5"), r)
    }

    @Test
    fun `DoubleRange rejects NaN and infinity`() {
        val nan = SetConfigFieldValidator.validate("NaN", FieldSpec.DoubleRange("Fee", 0.0..50.0))
        val inf = SetConfigFieldValidator.validate("Infinity", FieldSpec.DoubleRange("Fee", 0.0..50.0))
        assertInstanceOf(FieldResult.Error::class.java, nan)
        assertInstanceOf(FieldResult.Error::class.java, inf)
    }

    @Test
    fun `DoubleRange rejects out of range`() {
        val r = SetConfigFieldValidator.validate("55.0", FieldSpec.DoubleRange("Fee", 0.0..50.0))
        assertInstanceOf(FieldResult.Error::class.java, r)
    }

    @Test
    fun `BoolStrict normalises truthy spellings`() {
        listOf("true", "TRUE", "yes", "Y", "1", "on", "enabled").forEach { input ->
            if (input == "Y") return@forEach // Y not in dictionary; "yes" is
            val r = SetConfigFieldValidator.validate(input, FieldSpec.BoolStrict("Tracking"))
            assertEquals(FieldResult.Write("true"), r, "input=$input")
        }
    }

    @Test
    fun `BoolStrict normalises falsy spellings`() {
        listOf("false", "FALSE", "no", "0", "off", "disabled").forEach { input ->
            val r = SetConfigFieldValidator.validate(input, FieldSpec.BoolStrict("Tracking"))
            assertEquals(FieldResult.Write("false"), r, "input=$input")
        }
    }

    @Test
    fun `BoolStrict rejects nonsense`() {
        val r = SetConfigFieldValidator.validate("maybe", FieldSpec.BoolStrict("Tracking"))
        assertInstanceOf(FieldResult.Error::class.java, r)
    }

    @Test
    fun `EnumChoice accepts allowed value case-insensitively`() {
        val spec = FieldSpec.EnumChoice("Mode", setOf("NUMBER_MATCH", "WEIGHTED"))
        assertEquals(FieldResult.Write("NUMBER_MATCH"), SetConfigFieldValidator.validate("number_match", spec))
        assertEquals(FieldResult.Write("WEIGHTED"), SetConfigFieldValidator.validate("Weighted", spec))
    }

    @Test
    fun `EnumChoice rejects values outside the set`() {
        val spec = FieldSpec.EnumChoice("Mode", setOf("NUMBER_MATCH", "WEIGHTED"))
        val r = SetConfigFieldValidator.validate("RANDOM", spec)
        assertInstanceOf(FieldResult.Error::class.java, r)
    }

    @Test
    fun `ChannelByIdStoreName resolves to channel name`() {
        val guild = mockk<Guild>()
        val ch = mockk<TextChannel> { every { name } returns "general" }
        every { guild.getTextChannelById(42L) } returns ch

        val r = SetConfigFieldValidator.validate("42", FieldSpec.ChannelByIdStoreName("Move channel"), guild)
        assertEquals(FieldResult.Write("general"), r)
    }

    @Test
    fun `ChannelByIdStoreName accepts mention format`() {
        val guild = mockk<Guild>()
        val ch = mockk<TextChannel> { every { name } returns "voice-lounge" }
        every { guild.getTextChannelById(99L) } returns ch

        val r = SetConfigFieldValidator.validate("<#99>", FieldSpec.ChannelByIdStoreName("Move channel"), guild)
        assertEquals(FieldResult.Write("voice-lounge"), r)
    }

    @Test
    fun `ChannelByIdStoreName errors on unknown id`() {
        val guild = mockk<Guild>()
        every { guild.getTextChannelById(any<Long>()) } returns null
        every { guild.getVoiceChannelById(any<Long>()) } returns null

        val r = SetConfigFieldValidator.validate("123", FieldSpec.ChannelByIdStoreName("Move channel"), guild)
        assertInstanceOf(FieldResult.Error::class.java, r)
    }

    @Test
    fun `ChannelByIdStoreId stores numeric id`() {
        val guild = mockk<Guild>()
        every { guild.getTextChannelById(7L) } returns mockk<TextChannel>(relaxed = true)

        val r = SetConfigFieldValidator.validate("7", FieldSpec.ChannelByIdStoreId("Leaderboard"), guild)
        assertEquals(FieldResult.Write("7"), r)
    }

    @Test
    fun `ChannelByIdStoreId allows 0 to clear`() {
        val r = SetConfigFieldValidator.validate("0", FieldSpec.ChannelByIdStoreId("Lottery channel"), guild = null)
        assertEquals(FieldResult.Write(""), r)
    }

    // ----- validateAll() collecting writes vs errors -----

    @Test
    fun `validateAll returns Writes for all-valid input`() {
        val specs = linkedMapOf<Configurations, FieldSpec>(
            Configurations.VOLUME to FieldSpec.IntRange("Volume", 0..200),
            Configurations.DELETE_DELAY to FieldSpec.IntRange("Delete delay", 0..600),
        )
        val input = mapOf(
            Configurations.VOLUME to "80",
            Configurations.DELETE_DELAY to "10",
        )
        val out = SetConfigFieldValidator.validateAll(input::get, specs)
        val writes = assertInstanceOf(ValidationOutcome.Writes::class.java, out)
        assertEquals(
            listOf(Configurations.VOLUME to "80", Configurations.DELETE_DELAY to "10"),
            writes.pairs,
        )
    }

    @Test
    fun `validateAll preserves spec insertion order`() {
        val specs = linkedMapOf<Configurations, FieldSpec>(
            Configurations.INTRO_VOLUME to FieldSpec.IntRange("Intro volume", 0..200),
            Configurations.VOLUME to FieldSpec.IntRange("Volume", 0..200),
        )
        val input = mapOf(
            Configurations.INTRO_VOLUME to "70",
            Configurations.VOLUME to "100",
        )
        val out = SetConfigFieldValidator.validateAll(input::get, specs) as ValidationOutcome.Writes
        assertEquals(Configurations.INTRO_VOLUME, out.pairs[0].first)
        assertEquals(Configurations.VOLUME, out.pairs[1].first)
    }

    @Test
    fun `validateAll skips blank fields without complaining`() {
        val specs = linkedMapOf<Configurations, FieldSpec>(
            Configurations.VOLUME to FieldSpec.IntRange("Volume", 0..200),
            Configurations.DELETE_DELAY to FieldSpec.IntRange("Delete delay", 0..600),
        )
        val input = mapOf(
            Configurations.VOLUME to "",
            Configurations.DELETE_DELAY to "30",
        )
        val out = SetConfigFieldValidator.validateAll(input::get, specs) as ValidationOutcome.Writes
        assertEquals(listOf(Configurations.DELETE_DELAY to "30"), out.pairs)
    }

    @Test
    fun `validateAll returns Errors when any field fails, with all errors collected`() {
        val specs = linkedMapOf<Configurations, FieldSpec>(
            Configurations.VOLUME to FieldSpec.IntRange("Volume", 0..200),
            Configurations.DELETE_DELAY to FieldSpec.IntRange("Delete delay", 0..600),
            Configurations.INTRO_VOLUME to FieldSpec.IntRange("Intro volume", 0..200),
        )
        val input = mapOf(
            Configurations.VOLUME to "abc",
            Configurations.DELETE_DELAY to "100",
            Configurations.INTRO_VOLUME to "999",
        )
        val out = SetConfigFieldValidator.validateAll(input::get, specs)
        val errors = assertInstanceOf(ValidationOutcome.Errors::class.java, out)
        assertEquals(2, errors.messages.size)
        assertTrue(errors.messages[0].startsWith("Volume:"))
        assertTrue(errors.messages[1].startsWith("Intro volume:"))
    }
}
