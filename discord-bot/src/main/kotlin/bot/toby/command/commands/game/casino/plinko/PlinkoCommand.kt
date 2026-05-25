package bot.toby.command.commands.game.casino.plinko

import core.command.CommandContext
import database.dto.user.UserDto
import common.casino.plinko.Plinko
import database.service.casino.plinko.PlinkoService
import database.service.casino.plinko.PlinkoService.DropOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import bot.toby.command.commands.game.pvp.GameCommand
import bot.toby.command.commands.game.WagerCommandColors
import bot.toby.command.commands.game.WagerCommandEmbeds
import bot.toby.command.commands.game.WagerCommandFailure

/**
 * `/plinko risk:<LOW|MEDIUM|HIGH> stake:<int>` — drop a ball through an
 * 8-row peg board into one of 9 buckets. Higher risk profiles widen the
 * outer multipliers and add bust buckets in the middle. Calls through
 * to [PlinkoService.drop]; same path the web `/casino/{guildId}/plinko`
 * page uses, so the two surfaces can't drift on payout maths.
 */
@Component
class PlinkoCommand @Autowired constructor(
    private val plinkoService: PlinkoService
) : GameCommand {

    override val name: String = "plinko"
    override val description: String =
        "Drop a ball through ${Plinko.ROWS} rows into ${Plinko.BUCKETS} buckets. " +
            "Stake bounds per-guild (default ${Plinko.MIN_STAKE}-${Plinko.MAX_STAKE})."

    companion object {
        private const val OPT_RISK = "risk"
        private const val OPT_STAKE = "stake"
        private const val TITLE = "🟢 Plinko"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.STRING, OPT_RISK, "Risk profile (LOW / MEDIUM / HIGH)", true)
            .also { opt -> Plinko.Risk.entries.forEach { opt.addChoice(it.name, it.name) } },
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (per-guild bounds; service rejects out-of-range)", true)
            .setMinValue(1L)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        val guild = WagerCommandEmbeds.requireGuild(event, TITLE, deleteDelay) ?: return
        val riskName = WagerCommandEmbeds.requireOption(
            event, TITLE, OPT_RISK, "Pick a risk profile.", deleteDelay
        )?.asString ?: return
        val risk = Plinko.Risk.entries.firstOrNull { it.name == riskName.uppercase() } ?: run {
            WagerCommandEmbeds.replyError(
                event, TITLE,
                "Risk must be one of ${Plinko.Risk.entries.joinToString { it.name }}.",
                deleteDelay
            ); return
        }
        val stake = WagerCommandEmbeds.requireOption(
            event, TITLE, OPT_STAKE, "You must specify a stake.", deleteDelay
        )?.asLong ?: return

        val outcome = plinkoService.drop(requestingUserDto.discordId, guild.idLong, stake, risk)
        WagerCommandEmbeds.reply(event, embedFor(outcome), deleteDelay)
    }

    private fun embedFor(outcome: DropOutcome) = when (outcome) {
        is DropOutcome.Win -> EmbedBuilder()
            .setTitle("🟢 Bucket ${outcome.bucket} · ${formatMult(outcome.multiplier)}")
            .setDescription("Risk **${outcome.risk}** · won **+${outcome.net} credits**.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.WIN)
            .build()

        is DropOutcome.Lose -> EmbedBuilder()
            .setTitle("🟢 Bucket ${outcome.bucket} · ${formatMult(outcome.multiplier)}")
            .setDescription(
                "Risk **${outcome.risk}** · lost **${-outcome.net} credits**" +
                    if (outcome.payout > 0L) " (kept ${outcome.payout})." else "."
            )
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.LOSE)
            .build()

        is DropOutcome.Push -> EmbedBuilder()
            .setTitle("🟢 Bucket ${outcome.bucket} · 1×")
            .setDescription("Risk **${outcome.risk}** · stake refunded.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.LOSE)
            .build()

        is DropOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is DropOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is DropOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        DropOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }

    // Trim trailing zeros so 1.0× shows as "1×", 0.4× stays "0.4×".
    private fun formatMult(m: Double): String {
        if (m == m.toLong().toDouble()) return "${m.toLong()}×"
        return "${"%.2f".format(m).trimEnd('0').trimEnd('.')}×"
    }
}
