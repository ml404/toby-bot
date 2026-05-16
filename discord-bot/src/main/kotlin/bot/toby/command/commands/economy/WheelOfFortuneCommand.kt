package bot.toby.command.commands.economy

import core.command.CommandContext
import database.dto.UserDto
import database.economy.WheelOfFortune
import database.service.WheelOfFortuneService
import database.service.WheelOfFortuneService.SpinOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/wheel pick:<multiplier> stake:<int>` — pick a multiplier, spin the
 * Wheel of Fortune. Win `pick × stake` only if the wheel lands on the
 * chosen multiplier; otherwise the stake is lost. Calls through to
 * [WheelOfFortuneService.spin]; same path the web
 * `/casino/{guildId}/wheel` page uses.
 */
@Component
class WheelOfFortuneCommand @Autowired constructor(
    private val wheelService: WheelOfFortuneService
) : EconomyCommand {

    override val name: String = "wheel"
    override val description: String =
        "Pick a multiplier, spin the wheel. Stake bounds per-guild " +
            "(default ${WheelOfFortune.MIN_STAKE}-${WheelOfFortune.MAX_STAKE})."

    companion object {
        private const val OPT_PICK = "pick"
        private const val OPT_STAKE = "stake"
        private const val TITLE = "🎡 Wheel of Fortune"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.INTEGER, OPT_PICK, "Multiplier to bet on", true)
            .also { opt -> WheelOfFortune.PICKS.forEach { opt.addChoice("${it}×", it) } },
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (per-guild bounds; service rejects out-of-range)", true)
            .setMinValue(1L)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = WagerCommandEmbeds.requireGuild(event, TITLE, deleteDelay) ?: return
        val pick = WagerCommandEmbeds.requireOption(
            event, TITLE, OPT_PICK, "Pick a multiplier.", deleteDelay
        )?.asLong ?: return
        val stake = WagerCommandEmbeds.requireOption(
            event, TITLE, OPT_STAKE, "You must specify a stake.", deleteDelay
        )?.asLong ?: return

        val outcome = wheelService.spin(requestingUserDto.discordId, guild.idLong, stake, pick)
        WagerCommandEmbeds.reply(event, embedFor(outcome), deleteDelay)
    }

    private fun embedFor(outcome: SpinOutcome) = when (outcome) {
        is SpinOutcome.Win -> EmbedBuilder()
            .setTitle("🎡 Landed ${outcome.landedMultiplier}×")
            .setDescription(
                "You picked **${outcome.pickedMultiplier}×** and won " +
                    "**+${outcome.net} credits** on a ${outcome.stake} stake."
            )
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.WIN)
            .build()

        is SpinOutcome.Lose -> EmbedBuilder()
            .setTitle("🎡 Landed ${outcome.landedMultiplier}×")
            .setDescription("You picked **${outcome.pickedMultiplier}×**. Lost **${outcome.stake} credits**.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.LOSE)
            .build()

        is SpinOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is SpinOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is SpinOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        is SpinOutcome.InvalidPick -> WagerCommandEmbeds.errorEmbed(
            TITLE, "Pick must be one of ${outcome.picks.joinToString { "${it}×" }}."
        )
        SpinOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }
}
