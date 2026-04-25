package bot.toby.command.commands.economy

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.economy.ScratchCard
import database.economy.SlotMachine
import database.service.ScratchService
import database.service.ScratchService.ScratchOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

/**
 * `/scratch stake:<int>` — buy a scratchcard. Win on
 * [ScratchCard.MATCH_THRESHOLD]+ of any symbol; payouts scale with
 * match count. Calls through to [ScratchService.scratch]; same path
 * the web `/casino/{guildId}/scratch` page uses.
 */
@Component
class ScratchCommand @Autowired constructor(
    private val scratchService: ScratchService
) : EconomyCommand {

    override val name: String = "scratch"
    override val description: String =
        "Buy a ${ScratchCard.CELL_COUNT}-cell scratchcard. Match ${ScratchCard.MATCH_THRESHOLD}+ of any symbol. " +
            "Bet ${ScratchCard.MIN_STAKE}-${ScratchCard.MAX_STAKE} credits."

    companion object {
        private const val OPT_STAKE = "stake"
        private val WIN_COLOR = Color(87, 242, 135)
        private val LOSE_COLOR = Color(160, 160, 176)
        private val ERROR_COLOR = Color(237, 66, 69)
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (10-500)", true)
            .setMinValue(ScratchCard.MIN_STAKE)
            .setMaxValue(ScratchCard.MAX_STAKE)
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

        val outcome = scratchService.scratch(requestingUserDto.discordId, guild.idLong, stake)
        replyOutcome(event, outcome, deleteDelay)
    }

    private fun cellsLine(cells: List<SlotMachine.Symbol>): String =
        cells.joinToString(separator = " ") { it.display }

    private fun replyOutcome(
        event: SlashCommandInteractionEvent,
        outcome: ScratchOutcome,
        deleteDelay: Int
    ) {
        val embed = when (outcome) {
            is ScratchOutcome.Win -> EmbedBuilder()
                .setTitle("🎟️ ${cellsLine(outcome.cells)}")
                .setDescription(
                    "**${outcome.matchCount}× ${outcome.winningSymbol.display}** &mdash; " +
                        "won **+${outcome.net} credits**."
                )
                .addField("New balance", "${outcome.newBalance} credits", true)
                .setColor(WIN_COLOR)
                .build()

            is ScratchOutcome.Lose -> EmbedBuilder()
                .setTitle("🎟️ ${cellsLine(outcome.cells)}")
                .setDescription("No ${ScratchCard.MATCH_THRESHOLD}-of-a-kind. Lost **${outcome.stake} credits**.")
                .addField("New balance", "${outcome.newBalance} credits", true)
                .setColor(LOSE_COLOR)
                .build()

            is ScratchOutcome.InsufficientCredits -> errorEmbed(
                "Not enough credits. You need ${outcome.stake} but only have ${outcome.have}."
            )

            is ScratchOutcome.InsufficientCoinsForTopUp -> errorEmbed(
                "Not enough credits, and not enough TOBY to cover. " +
                    "Need ${outcome.needed} TOBY, you have ${outcome.have}."
            )

            is ScratchOutcome.InvalidStake -> errorEmbed(
                "Stake must be between ${outcome.min} and ${outcome.max} credits."
            )

            ScratchOutcome.UnknownUser -> errorEmbed(
                "No user record yet. Try another TobyBot command first."
            )
        }
        event.hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun errorEmbed(message: String) = EmbedBuilder()
        .setTitle("🎟️ Scratch")
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
