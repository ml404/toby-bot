package bot.toby.command.commands.economy

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.economy.Dice
import database.service.DiceService
import database.service.DiceService.RollOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

/**
 * `/dice prediction:<1-6> stake:<int>` — pick a number, roll the die,
 * 5× payout on a hit (1/6 odds, ~17% house edge). Calls through to
 * [DiceService.roll]; same path the web `/casino/{guildId}/dice` page
 * uses.
 */
@Component
class DiceCommand @Autowired constructor(
    private val diceService: DiceService
) : EconomyCommand {

    override val name: String = "dice"
    override val description: String =
        "Pick a number 1-6, roll a die. Bet ${Dice.MIN_STAKE}-${Dice.MAX_STAKE} credits."

    companion object {
        private const val OPT_PREDICTION = "prediction"
        private const val OPT_STAKE = "stake"
        private val WIN_COLOR = Color(87, 242, 135)
        private val LOSE_COLOR = Color(160, 160, 176)
        private val ERROR_COLOR = Color(237, 66, 69)
        private val DICE_FACES = mapOf(
            1 to "⚀", 2 to "⚁", 3 to "⚂", 4 to "⚃", 5 to "⚄", 6 to "⚅"
        )
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.INTEGER, OPT_PREDICTION, "Number to predict (1-6)", true)
            .setMinValue(1L)
            .setMaxValue(Dice.DEFAULT_SIDES.toLong()),
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (10-500)", true)
            .setMinValue(Dice.MIN_STAKE)
            .setMaxValue(Dice.MAX_STAKE)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            replyError(event, "This command can only be used in a server.", deleteDelay); return
        }
        val predicted = event.getOption(OPT_PREDICTION)?.asLong?.toInt() ?: run {
            replyError(event, "Pick a number 1-6.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            replyError(event, "You must specify a stake.", deleteDelay); return
        }

        val outcome = diceService.roll(requestingUserDto.discordId, guild.idLong, stake, predicted)
        replyOutcome(event, outcome, deleteDelay)
    }

    private fun face(n: Int): String = DICE_FACES[n] ?: n.toString()

    private fun replyOutcome(
        event: SlashCommandInteractionEvent,
        outcome: RollOutcome,
        deleteDelay: Int
    ) {
        val embed = when (outcome) {
            is RollOutcome.Win -> EmbedBuilder()
                .setTitle("🎲 ${face(outcome.landed)} (${outcome.landed})")
                .setDescription("You called **${outcome.predicted}** and won **+${outcome.net} credits** (${outcome.multiplier()}× on a ${outcome.stake} stake).")
                .addField("New balance", "${outcome.newBalance} credits", true)
                .setColor(WIN_COLOR)
                .build()

            is RollOutcome.Lose -> EmbedBuilder()
                .setTitle("🎲 ${face(outcome.landed)} (${outcome.landed})")
                .setDescription("You called **${outcome.predicted}**. Lost **${outcome.stake} credits**.")
                .addField("New balance", "${outcome.newBalance} credits", true)
                .setColor(LOSE_COLOR)
                .build()

            is RollOutcome.InsufficientCredits -> errorEmbed(
                "Not enough credits. You need ${outcome.stake} but only have ${outcome.have}."
            )

            is RollOutcome.InvalidStake -> errorEmbed(
                "Stake must be between ${outcome.min} and ${outcome.max} credits."
            )

            is RollOutcome.InvalidPrediction -> errorEmbed(
                "Pick a number between ${outcome.min} and ${outcome.max}."
            )

            RollOutcome.UnknownUser -> errorEmbed(
                "No user record yet. Try another TobyBot command first."
            )
        }
        event.hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    // payout / stake = multiplier — derived for the "5× on" string in the win embed.
    private fun RollOutcome.Win.multiplier(): Long = if (stake > 0L) payout / stake else 0L

    private fun errorEmbed(message: String) = EmbedBuilder()
        .setTitle("🎲 Dice")
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
