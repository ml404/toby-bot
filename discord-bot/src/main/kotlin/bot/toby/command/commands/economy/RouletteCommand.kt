package bot.toby.command.commands.economy

import core.command.CommandContext
import database.dto.UserDto
import common.economy.Roulette
import database.service.RouletteService
import database.service.RouletteService.SpinOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/roulette stake:<int> bet:<choice> [number:<0-36>]` — European single-
 * zero roulette. Calls through to [RouletteService.spin], which is the
 * same path the web `/casino/{guildId}/roulette` page uses, so the
 * Discord and web surfaces can't drift on payout maths or balance
 * debit/credit semantics.
 */
@Component
class RouletteCommand @Autowired constructor(
    private val rouletteService: RouletteService,
) : EconomyCommand {

    override val name: String = "roulette"
    override val description: String =
        "Spin a European single-zero roulette wheel. Stake bounds are per-guild " +
            "(default ${Roulette.MIN_STAKE}-${Roulette.MAX_STAKE})."

    companion object {
        private const val OPT_STAKE = "stake"
        private const val OPT_BET = "bet"
        private const val OPT_NUMBER = "number"
        private const val TITLE = "🎡 Roulette"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.STRING, OPT_BET, "What to bet on", true).apply {
            Roulette.Bet.entries.forEach { addChoice(it.display, it.name) }
        },
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (per-guild bounds; service rejects out-of-range)", true)
            .setMinValue(1L),
        OptionData(OptionType.INTEGER, OPT_NUMBER, "Pocket 0-36 (only required for a STRAIGHT bet)", false)
            .setMinValue(0L)
            .setMaxValue(Roulette.MAX_NUMBER.toLong()),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "This command can only be used in a server.", deleteDelay); return
        }
        val bet = parseBet(event.getOption(OPT_BET)?.asString) ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "Pick a bet from the list.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "You must specify a stake.", deleteDelay); return
        }
        val number = event.getOption(OPT_NUMBER)?.asLong?.toInt()
        if (bet == Roulette.Bet.STRAIGHT && (number == null || number !in 0..Roulette.MAX_NUMBER)) {
            WagerCommandEmbeds.replyError(
                event, TITLE,
                "A straight bet needs a `number` between 0 and ${Roulette.MAX_NUMBER}.",
                deleteDelay,
            ); return
        }

        val outcome = rouletteService.spin(
            requestingUserDto.discordId, guild.idLong, stake, bet, number,
        )
        WagerCommandEmbeds.reply(event, embedFor(outcome), deleteDelay)
    }

    private fun parseBet(raw: String?): Roulette.Bet? =
        raw?.let { runCatching { Roulette.Bet.valueOf(it) }.getOrNull() }

    private fun embedFor(outcome: SpinOutcome) = when (outcome) {
        is SpinOutcome.Win -> EmbedBuilder()
            .setTitle("🎡 ${pocketLine(outcome.landed, outcome.color)} — ${outcome.bet.display}")
            .setDescription(
                "Called **${callLabel(outcome.bet, outcome.straightNumber)}**. " +
                    "**+${outcome.net} credits** (${outcome.multiplier}× on a ${outcome.stake} stake)."
            )
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.WIN)
            .build()

        is SpinOutcome.Lose -> EmbedBuilder()
            .setTitle("🎡 ${pocketLine(outcome.landed, outcome.color)} — ${outcome.bet.display}")
            .setDescription(
                "Called **${callLabel(outcome.bet, outcome.straightNumber)}**. " +
                    "Lost **${outcome.stake} credits**."
            )
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.LOSE)
            .build()

        is SpinOutcome.InvalidNumber -> WagerCommandEmbeds.errorEmbed(
            TITLE, "A straight bet needs a `number` between ${outcome.min} and ${outcome.max}."
        )

        is SpinOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is SpinOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is SpinOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        SpinOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }

    private fun pocketLine(pocket: Int, color: Roulette.Color): String =
        "${colorEmoji(color)} $pocket"

    private fun colorEmoji(color: Roulette.Color): String = when (color) {
        Roulette.Color.RED -> "🟥"
        Roulette.Color.BLACK -> "⬛"
        Roulette.Color.GREEN -> "🟩"
    }

    private fun callLabel(bet: Roulette.Bet, number: Int?): String =
        if (bet == Roulette.Bet.STRAIGHT && number != null) "${bet.display} ($number)" else bet.display
}
