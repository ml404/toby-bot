package bot.toby.command.commands.dnd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class RollEmbedsTest {

    @Test
    fun `single-die natural max gets the crit colour`() {
        val e = RollEmbeds.resultEmbed(20, 1, 0, listOf(20), "Asker")
        assertEquals(RollEmbeds.CRIT.rgb, e.colorRaw)
    }

    @Test
    fun `single-die natural 1 gets the fumble colour`() {
        val e = RollEmbeds.resultEmbed(20, 1, 0, listOf(1), "Asker")
        assertEquals(RollEmbeds.FUMBLE.rgb, e.colorRaw)
    }

    @Test
    fun `multi-die rolls never trigger crit or fumble colours`() {
        val e = RollEmbeds.resultEmbed(20, 2, 0, listOf(20, 20), "Asker")
        assertEquals(RollEmbeds.NEUTRAL.rgb, e.colorRaw)
    }

    @Test
    fun `multi-die rolls include a Per-die breakdown field`() {
        val e = RollEmbeds.resultEmbed(6, 3, 0, listOf(2, 5, 6), "Asker")
        val perDie = e.fields.singleOrNull { it.name == "Per-die" }
        assertTrue(perDie != null && perDie.value!!.contains("2") && perDie.value!!.contains("5") && perDie.value!!.contains("6"))
    }

    @Test
    fun `single-die rolls skip the Per-die breakdown to keep the embed compact`() {
        val e = RollEmbeds.resultEmbed(20, 1, 0, listOf(14), "Asker")
        assertEquals(0, e.fields.count { it.name == "Per-die" })
    }

    @Test
    fun `title encodes the modifier sign`() {
        val pos = RollEmbeds.resultEmbed(20, 1, 3, listOf(10), "Asker")
        assertEquals("1d20 + 3", pos.title)
        val neg = RollEmbeds.resultEmbed(20, 1, -2, listOf(10), "Asker")
        assertEquals("1d20 - 2", neg.title)
        val zero = RollEmbeds.resultEmbed(20, 1, 0, listOf(10), "Asker")
        assertEquals("1d20", zero.title)
    }
}
