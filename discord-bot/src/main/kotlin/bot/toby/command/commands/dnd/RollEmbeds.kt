package bot.toby.command.commands.dnd

import common.discord.embed
import common.discord.field
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * Embed factory for the `/roll` reply. Pulled out of [RollCommand] so
 * the colour/format rules live in one testable place and the command
 * itself just deals with the JDA wiring.
 */
internal object RollEmbeds {
    /** Discord blurple — the default "nothing interesting happened" roll. */
    val NEUTRAL: Color = Color(88, 101, 242)

    /** Bright green — natural max on a single die ("crit"). */
    val CRIT: Color = Color(87, 242, 135)

    /** Discord red — natural 1 on a single die ("fumble"). */
    val FUMBLE: Color = Color(237, 66, 69)

    /**
     * Build the result embed. [rolls] is the per-die breakdown; sum is
     * recomputed so callers can't accidentally pass an inconsistent
     * total.
     */
    fun resultEmbed(
        diceValue: Int,
        diceToRoll: Int,
        modifier: Int,
        rolls: List<Int>,
        askedBy: String,
    ): MessageEmbed {
        val sum = rolls.sum()
        val total = sum + modifier
        val label = buildString {
            append(diceToRoll); append('d'); append(diceValue)
            when {
                modifier > 0 -> append(" + ").append(modifier)
                modifier < 0 -> append(" - ").append(-modifier)
            }
        }
        return embed(color = pickColor(diceValue, diceToRoll, rolls)) {
            setAuthor("🎲  Dice roller")
            setTitle(label)
            setDescription("> **Total:** $total" + if (modifier != 0) "  *(rolls $sum, mod $modifier)*" else "")
            if (rolls.size > 1) {
                field(
                    name = "Per-die",
                    value = rolls.joinToString("  •  ") { it.toString() },
                    inline = false,
                )
            }
            setFooter("Rolled by $askedBy")
        }
    }

    private fun pickColor(diceValue: Int, diceToRoll: Int, rolls: List<Int>): Color {
        if (diceToRoll != 1 || rolls.size != 1) return NEUTRAL
        return when (rolls.single()) {
            diceValue -> CRIT
            1 -> FUMBLE
            else -> NEUTRAL
        }
    }
}
