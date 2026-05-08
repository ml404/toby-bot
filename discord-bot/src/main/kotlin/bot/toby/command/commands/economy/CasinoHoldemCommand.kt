package bot.toby.command.commands.economy

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.poker.CasinoHoldem
import database.poker.CasinoHoldemTableRegistry
import database.service.CasinoHoldemService
import database.service.CasinoHoldemService.DealOutcome
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/casinoholdem stake:<n>` — singleplayer Casino Hold'em vs the
 * dealer. The flow mirrors `/blackjack solo`: stake debited at deal,
 * one mid-hand decision (CALL or FOLD) drives settlement through
 * [CasinoHoldemService].
 */
@Component
class CasinoHoldemCommand @Autowired constructor(
    private val service: CasinoHoldemService,
    private val tableRegistry: CasinoHoldemTableRegistry,
) : EconomyCommand {

    override val name: String = "casinoholdem"
    override val description: String =
        "Play one hand of Casino Hold'em — ante, see the flop, then call or fold against the dealer."

    companion object {
        private const val OPT_STAKE = "stake"
        private const val OPT_AUTO_TOPUP = "auto_topup"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.INTEGER, OPT_STAKE,
            "Credits to ante (per-guild bounds; service rejects out-of-range; default ${CasinoHoldem.MIN_STAKE}-${CasinoHoldem.MAX_STAKE})", true)
            .setMinValue(1L),
        OptionData(OptionType.BOOLEAN, OPT_AUTO_TOPUP,
            "Sell TOBY at market to cover any credit shortfall", false),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            replyError(event, "This command can only be used in a server.", deleteDelay); return
        }
        val stake = event.getOption(OPT_STAKE)?.asLong ?: run {
            replyError(event, "You must specify a stake.", deleteDelay); return
        }
        val autoTopUp = event.getOption(OPT_AUTO_TOPUP)?.asBoolean ?: false

        when (val outcome = service.dealSolo(requestingUserDto.discordId, guild.idLong, stake, autoTopUp)) {
            is DealOutcome.Dealt -> {
                val table = tableRegistry.get(outcome.tableId)
                    ?: return replyError(event, "Hand vanished.", deleteDelay)
                event.hook.sendMessageEmbeds(CasinoHoldemEmbeds.dealEmbed(table))
                    .addComponents(actionRow(outcome.tableId))
                    .queue()
            }
            is DealOutcome.InvalidStake -> replyFailure(
                event, WagerCommandFailure.InvalidStake(outcome.min, outcome.max), deleteDelay
            )
            is DealOutcome.InsufficientCredits -> replyFailure(
                event, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have), deleteDelay
            )
            is DealOutcome.InsufficientCoinsForTopUp -> replyFailure(
                event, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have), deleteDelay
            )
            DealOutcome.UnknownUser -> replyFailure(
                event, WagerCommandFailure.UnknownUser, deleteDelay
            )
            is DealOutcome.OnCooldown -> replyFailure(
                event, WagerCommandFailure.OnCooldown(outcome.remainingMs), deleteDelay
            )
        }
    }

    private fun actionRow(tableId: Long): ActionRow = ActionRow.of(
        Button.success(
            CasinoHoldemEmbeds.buttonId(CasinoHoldemEmbeds.Action.CALL, tableId),
            "Call (${CasinoHoldem.CALL_MULTIPLE}× stake)"
        ),
        Button.danger(
            CasinoHoldemEmbeds.buttonId(CasinoHoldemEmbeds.Action.FOLD, tableId),
            "Fold"
        ),
    )

    private fun replyError(
        event: SlashCommandInteractionEvent,
        message: String,
        deleteDelay: Int,
    ) {
        event.hook.sendMessageEmbeds(CasinoHoldemEmbeds.errorEmbed(message))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun replyFailure(
        event: SlashCommandInteractionEvent,
        failure: WagerCommandFailure,
        deleteDelay: Int,
    ) {
        event.hook.sendMessageEmbeds(WagerCommandEmbeds.failureEmbed(CasinoHoldemEmbeds.TITLE, failure))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
