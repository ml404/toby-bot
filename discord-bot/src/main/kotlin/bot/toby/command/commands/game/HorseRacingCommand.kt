package bot.toby.command.commands.game

import core.command.CommandContext
import database.dto.UserDto
import common.economy.HorseRacing
import database.service.HorseRacingService
import database.service.HorseRacingService.RaceOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/horse-racing stake:<int> bet:<WIN|PLACE|SHOW> horse:<1-6>` — six-horse
 * race with Win/Place/Show fixed-odds betting. Calls through to
 * [HorseRacingService.race], which is the same path the web
 * `/casino/{guildId}/horse-racing` page uses, so the Discord and web
 * surfaces can't drift on payout maths or balance debit/credit semantics.
 */
@Component
class HorseRacingCommand @Autowired constructor(
    private val horseRacingService: HorseRacingService,
) : GameCommand {

    override val name: String = "horse-racing"
    override val description: String =
        "Race six horses; bet Win (1st), Place (top 2), or Show (top 3) on a single horse."

    companion object {
        private const val OPT_STAKE = "stake"
        private const val OPT_BET = "bet"
        private const val OPT_HORSE = "horse"
        private const val TITLE = "🐎 Horse Racing"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.STRING, OPT_BET, "Bet type", true).apply {
            HorseRacing.Bet.entries.forEach { addChoice(it.display, it.name) }
        },
        OptionData(OptionType.INTEGER, OPT_HORSE, "Horse to bet on (1-${HorseRacing.FIELD_SIZE})", true)
            .setMinValue(1L)
            .setMaxValue(HorseRacing.FIELD_SIZE.toLong()),
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (per-guild bounds; service rejects out-of-range)", true)
            .setMinValue(1L),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = WagerCommandEmbeds.requireGuild(event, TITLE, deleteDelay) ?: return
        val bet = parseBet(event.getOption(OPT_BET)?.asString) ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "Pick a bet from the list.", deleteDelay); return
        }
        val horse = event.getOption(OPT_HORSE)?.asLong?.toInt() ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "You must pick a horse.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "You must specify a stake.", deleteDelay); return
        }

        val outcome = horseRacingService.race(
            requestingUserDto.discordId, guild.idLong, stake, horse, bet,
        )
        WagerCommandEmbeds.reply(event, embedFor(outcome), deleteDelay)
    }

    private fun parseBet(raw: String?): HorseRacing.Bet? =
        raw?.let { runCatching { HorseRacing.Bet.valueOf(it) }.getOrNull() }

    private fun embedFor(outcome: RaceOutcome) = when (outcome) {
        is RaceOutcome.Win -> EmbedBuilder()
            .setTitle("$TITLE — ${pickedLabel(outcome.pickedHorse, outcome.bet)}")
            .setDescription(
                "${finishLine(outcome.finishingOrder)}\n\n" +
                    "**+${outcome.net} credits** " +
                    "(${WagerCommandEmbeds.multiplierLabel(outcome.multiplier)} on a ${outcome.stake} stake)."
            )
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.WIN)
            .build()

        is RaceOutcome.Lose -> EmbedBuilder()
            .setTitle("$TITLE — ${pickedLabel(outcome.pickedHorse, outcome.bet)}")
            .setDescription(
                "${finishLine(outcome.finishingOrder)}\n\n" +
                    "Lost **${outcome.stake} credits**."
            )
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.LOSE)
            .build()

        is RaceOutcome.InvalidHorse -> WagerCommandEmbeds.errorEmbed(
            TITLE, "Pick a horse between ${outcome.min} and ${outcome.max}."
        )

        is RaceOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is RaceOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is RaceOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        RaceOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }

    private fun pickedLabel(pickedHorse: Int, bet: HorseRacing.Bet): String {
        val profile = HorseRacing.horse(pickedHorse)
        return "${bet.display} on H$pickedHorse ${profile.name}"
    }

    private fun finishLine(order: List<Int>): String {
        // Always show the podium with medals; tail of the field follows.
        val medals = listOf("🥇", "🥈", "🥉")
        return order.mapIndexed { idx, horseIdx ->
            val profile = HorseRacing.horse(horseIdx)
            val prefix = medals.getOrNull(idx) ?: "${idx + 1}."
            "$prefix H$horseIdx ${profile.name}"
        }.joinToString(" · ")
    }
}
