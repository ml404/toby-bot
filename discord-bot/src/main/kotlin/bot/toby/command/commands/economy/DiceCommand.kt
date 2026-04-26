package bot.toby.command.commands.economy

import core.command.CommandContext
import database.dto.UserDto
import database.economy.Dice
import database.service.DiceService
import database.service.DiceService.RollOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

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
        private const val TITLE = "🎲 Dice"
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
            WagerCommandEmbeds.replyError(event, TITLE, "This command can only be used in a server.", deleteDelay); return
        }
        val predicted = event.getOption(OPT_PREDICTION)?.asLong?.toInt() ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "Pick a number 1-6.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "You must specify a stake.", deleteDelay); return
        }

        val outcome = diceService.roll(requestingUserDto.discordId, guild.idLong, stake, predicted)
        WagerCommandEmbeds.reply(event, embedFor(outcome), deleteDelay)
    }

    private fun face(n: Int): String = DICE_FACES[n] ?: n.toString()

    private fun embedFor(outcome: RollOutcome) = when (outcome) {
        is RollOutcome.Win -> EmbedBuilder()
            .setTitle("🎲 ${face(outcome.landed)} (${outcome.landed})")
            .setDescription("You called **${outcome.predicted}** and won **+${outcome.net} credits** (${outcome.multiplier()}× on a ${outcome.stake} stake).")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.WIN)
            .build()

        is RollOutcome.Lose -> EmbedBuilder()
            .setTitle("🎲 ${face(outcome.landed)} (${outcome.landed})")
            .setDescription("You called **${outcome.predicted}**. Lost **${outcome.stake} credits**.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.LOSE)
            .build()

        is RollOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is RollOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is RollOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        // Game-specific failure (no shared analogue): pick-a-number-N range error.
        is RollOutcome.InvalidPrediction -> WagerCommandEmbeds.errorEmbed(
            TITLE, "Pick a number between ${outcome.min} and ${outcome.max}."
        )
        RollOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }

    // payout / stake = multiplier — derived for the "5× on" string in the win embed.
    private fun RollOutcome.Win.multiplier(): Long = if (stake > 0L) payout / stake else 0L
}
