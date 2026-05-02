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

    private const val TITLE = "🃏 High-Low"
    private val ANCHOR_COLOR = Color(88, 101, 242)

    fun cardLabel(n: Int): String = when (n) {
        1 -> "A"
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        else -> n.toString()
    }

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
                    "Higher pays **${WagerCommandEmbeds.multiplierLabel(higherMultiplier)}**, " +
                    "lower pays **${WagerCommandEmbeds.multiplierLabel(lowerMultiplier)}**."
            )
            .setColor(ANCHOR_COLOR)
            .build()

    fun outcomeEmbed(outcome: PlayOutcome): MessageEmbed = when (outcome) {
        is PlayOutcome.Win -> EmbedBuilder()
            .setTitle("🃏 ${cardLabel(outcome.anchor)} → ${cardLabel(outcome.next)}")
            .setDescription(
                "You called **${outcome.direction.display}** at " +
                    "**${WagerCommandEmbeds.multiplierLabel(outcome.multiplier)}** and won **+${outcome.net} credits**."
            )
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.WIN)
            .build()

        is PlayOutcome.Lose -> EmbedBuilder()
            .setTitle("🃏 ${cardLabel(outcome.anchor)} → ${cardLabel(outcome.next)}")
            .setDescription("You called **${outcome.direction.display}**. Lost **${outcome.stake} credits**.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.LOSE)
            .build()

        is PlayOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is PlayOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is PlayOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        PlayOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }

    fun errorEmbed(message: String): MessageEmbed = WagerCommandEmbeds.errorEmbed(TITLE, message)
}
