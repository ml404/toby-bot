package bot.toby.command.commands.economy

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.economy.Highlow
import database.service.HighlowService
import database.service.HighlowService.PlayOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

/**
 * `/highlow direction:<HIGHER|LOWER> stake:<int>` — pre-commit the
 * direction; the embed reveals both cards. 2× payout on a correct
 * call, tie loses. Calls through to [HighlowService.play]; same path
 * the web `/casino/{guildId}/highlow` page uses.
 */
@Component
class HighlowCommand @Autowired constructor(
    private val highlowService: HighlowService
) : EconomyCommand {

    override val name: String = "highlow"
    override val description: String =
        "Predict if the next card is higher or lower than the anchor. Bet ${Highlow.MIN_STAKE}-${Highlow.MAX_STAKE} credits."

    companion object {
        private const val OPT_DIRECTION = "direction"
        private const val OPT_STAKE = "stake"
        private const val DIR_HIGHER = "HIGHER"
        private const val DIR_LOWER = "LOWER"
        private val WIN_COLOR = Color(87, 242, 135)
        private val LOSE_COLOR = Color(160, 160, 176)
        private val ERROR_COLOR = Color(237, 66, 69)
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.STRING, OPT_DIRECTION, "Higher or lower than the anchor card", true)
            .addChoice("Higher", DIR_HIGHER)
            .addChoice("Lower", DIR_LOWER),
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (10-500)", true)
            .setMinValue(Highlow.MIN_STAKE)
            .setMaxValue(Highlow.MAX_STAKE)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            replyError(event, "This command can only be used in a server.", deleteDelay); return
        }
        val direction = parseDirection(event.getOption(OPT_DIRECTION)?.asString) ?: run {
            replyError(event, "Pick a direction: higher or lower.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            replyError(event, "You must specify a stake.", deleteDelay); return
        }

        val outcome = highlowService.play(requestingUserDto.discordId, guild.idLong, stake, direction)
        replyOutcome(event, outcome, deleteDelay)
    }

    private fun parseDirection(raw: String?): Highlow.Direction? = when (raw) {
        DIR_HIGHER -> Highlow.Direction.HIGHER
        DIR_LOWER -> Highlow.Direction.LOWER
        else -> null
    }

    private fun cardLabel(n: Int): String = when (n) {
        1 -> "A"
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        else -> n.toString()
    }

    private fun replyOutcome(
        event: SlashCommandInteractionEvent,
        outcome: PlayOutcome,
        deleteDelay: Int
    ) {
        val embed = when (outcome) {
            is PlayOutcome.Win -> EmbedBuilder()
                .setTitle("🃏 ${cardLabel(outcome.anchor)} → ${cardLabel(outcome.next)}")
                .setDescription("You called **${outcome.direction.display}** and won **+${outcome.net} credits**.")
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
        event.hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun errorEmbed(message: String) = EmbedBuilder()
        .setTitle("🃏 High-Low")
        .setDescription(message)
        .setColor(ERROR_COLOR)
        .build()

    private fun replyError(
        event: SlashCommandInteractionEvent,
        message: String,
        deleteDelay: Int
    ) {
        event.hook.sendMessageEmbeds(errorEmbed(message)).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
