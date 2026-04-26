package bot.toby.command.commands.economy

import database.economy.Highlow
import database.service.HighlowService.PlayOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * Shared embed/component plumbing for the Discord `/highlow` flow.
 * The slash command uses [anchorEmbed] + [directionButtonId] to deal
 * the first card; [HighlowButton] uses [outcomeEmbed] to render the
 * resolution.
 */
internal object HighlowEmbeds {

    const val BUTTON_NAME = "highlow"
    private const val BUTTON_DELIM = ":"

    private val ANCHOR_COLOR = Color(88, 101, 242)
    private val WIN_COLOR = Color(87, 242, 135)
    private val LOSE_COLOR = Color(160, 160, 176)
    private val ERROR_COLOR = Color(237, 66, 69)

    fun cardLabel(n: Int): String = when (n) {
        1 -> "A"
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        else -> n.toString()
    }

    /** "1.50×"-style label for direction buttons / embed copy. */
    fun multiplierLabel(multiplier: Double): String = "%.2f×".format(multiplier)

    fun directionButtonId(direction: Highlow.Direction, anchor: Int, stake: Long, userId: Long): String =
        listOf(BUTTON_NAME, direction.name, anchor.toString(), stake.toString(), userId.toString())
            .joinToString(BUTTON_DELIM)

    data class ParsedButtonId(
        val direction: Highlow.Direction,
        val anchor: Int,
        val stake: Long,
        val userId: Long
    )

    fun parseButtonId(componentId: String): ParsedButtonId? {
        val parts = componentId.split(BUTTON_DELIM)
        if (parts.size != 5 || !parts[0].equals(BUTTON_NAME, ignoreCase = true)) return null
        val direction = runCatching { Highlow.Direction.valueOf(parts[1].uppercase()) }.getOrNull() ?: return null
        val anchor = parts[2].toIntOrNull() ?: return null
        val stake = parts[3].toLongOrNull() ?: return null
        val userId = parts[4].toLongOrNull() ?: return null
        return ParsedButtonId(direction, anchor, stake, userId)
    }

    fun anchorEmbed(anchor: Int, stake: Long, higherMultiplier: Double, lowerMultiplier: Double): MessageEmbed =
        EmbedBuilder()
            .setTitle("🃏 Anchor: ${cardLabel(anchor)}")
            .setDescription(
                "Stake **$stake credits**. Will the next card be **higher** or **lower**?\n" +
                    "Higher pays **${multiplierLabel(higherMultiplier)}**, " +
                    "lower pays **${multiplierLabel(lowerMultiplier)}**."
            )
            .setColor(ANCHOR_COLOR)
            .build()

    fun outcomeEmbed(outcome: PlayOutcome): MessageEmbed = when (outcome) {
        is PlayOutcome.Win -> EmbedBuilder()
            .setTitle("🃏 ${cardLabel(outcome.anchor)} → ${cardLabel(outcome.next)}")
            .setDescription(
                "You called **${outcome.direction.display}** at " +
                    "**${multiplierLabel(outcome.multiplier)}** and won **+${outcome.net} credits**."
            )
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WIN_COLOR)
            .build()

        is PlayOutcome.Lose -> EmbedBuilder()
            .setTitle("🃏 ${cardLabel(outcome.anchor)} → ${cardLabel(outcome.next)}")
            .setDescription("You called **${outcome.direction.display}**. Lost **${outcome.stake} credits**.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(LOSE_COLOR)
            .build()

        is PlayOutcome.InsufficientCredits -> errorEmbed(
            "Not enough credits. You need ${outcome.stake} but only have ${outcome.have}."
        )

        is PlayOutcome.InsufficientCoinsForTopUp -> errorEmbed(
            "Not enough credits, and not enough TOBY to cover. " +
                "Need ${outcome.needed} TOBY, you have ${outcome.have}."
        )

        is PlayOutcome.InvalidStake -> errorEmbed(
            "Stake must be between ${outcome.min} and ${outcome.max} credits."
        )

        PlayOutcome.UnknownUser -> errorEmbed(
            "No user record yet. Try another TobyBot command first."
        )
    }

    fun errorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("🃏 High-Low")
        .setDescription(message)
        .setColor(ERROR_COLOR)
        .build()
}
