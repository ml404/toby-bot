package bot.toby.command.commands.economy

import core.command.CommandContext
import database.dto.UserDto
import database.economy.SlotMachine
import database.service.SlotsService
import database.service.SlotsService.SpinOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/slots stake:<int>` — first credit-spend gambling minigame. Calls
 * through to [SlotsService.spin], which is the same path the web
 * `/casino/{guildId}/slots` page uses, so the Discord and web surfaces
 * can't drift on payout maths or balance debit/credit semantics.
 */
@Component
class SlotsCommand @Autowired constructor(
    private val slotsService: SlotsService
) : EconomyCommand {

    override val name: String = "slots"
    override val description: String =
        "Spin a 3-reel slot machine. Bet ${SlotMachine.MIN_STAKE}-${SlotMachine.MAX_STAKE} credits per pull."

    companion object {
        private const val OPT_STAKE = "stake"
        private const val TITLE = "🎰 Slots"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (10–500)", true)
            .setMinValue(SlotMachine.MIN_STAKE)
            .setMaxValue(SlotMachine.MAX_STAKE)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "This command can only be used in a server.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            WagerCommandEmbeds.replyError(event, TITLE, "You must specify a stake.", deleteDelay); return
        }

        val outcome = slotsService.spin(requestingUserDto.discordId, guild.idLong, stake)
        WagerCommandEmbeds.reply(event, embedFor(outcome), deleteDelay)
    }

    private fun embedFor(outcome: SpinOutcome) = when (outcome) {
        is SpinOutcome.Win -> EmbedBuilder()
            .setTitle("🎰 ${reelLine(outcome.symbols)}")
            .setDescription("**+${outcome.net} credits** (${outcome.multiplier}× on a ${outcome.stake} stake)")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.WIN)
            .build()

        is SpinOutcome.Lose -> EmbedBuilder()
            .setTitle("🎰 ${reelLine(outcome.symbols)}")
            .setDescription("Lost **${outcome.stake} credits**.")
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
        SpinOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }

    private fun reelLine(symbols: List<SlotMachine.Symbol>): String =
        symbols.joinToString(separator = " ") { it.display }
}
