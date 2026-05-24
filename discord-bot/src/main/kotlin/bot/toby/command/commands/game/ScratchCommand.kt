package bot.toby.command.commands.game

import core.command.CommandContext
import database.dto.UserDto
import common.economy.ScratchCard
import common.economy.SlotMachine
import database.service.casino.scratch.ScratchService
import database.service.casino.scratch.ScratchService.ScratchOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/scratch stake:<int>` — buy a scratchcard. Win on
 * [ScratchCard.MATCH_THRESHOLD]+ of any symbol; payouts scale with
 * match count. Calls through to [ScratchService.scratch]; same path
 * the web `/casino/{guildId}/scratch` page uses.
 */
@Component
class ScratchCommand @Autowired constructor(
    private val scratchService: ScratchService
) : GameCommand {

    override val name: String = "scratch"
    override val description: String =
        "Buy a ${ScratchCard.CELL_COUNT}-cell scratchcard. Match ${ScratchCard.MATCH_THRESHOLD}+ of any symbol. " +
            "Stake bounds are per-guild (default ${ScratchCard.MIN_STAKE}-${ScratchCard.MAX_STAKE})."

    companion object {
        private const val OPT_STAKE = "stake"
        private const val TITLE = "🎟️ Scratch"
    }

    override val optionData: List<OptionData> = listOf(
        OptionData(OptionType.INTEGER, OPT_STAKE, "Credits to wager (per-guild bounds; service rejects out-of-range)", true)
            .setMinValue(1L)
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = WagerCommandEmbeds.requireGuild(event, TITLE, deleteDelay) ?: return
        val stake = WagerCommandEmbeds.requireOption(
            event, TITLE, OPT_STAKE, "You must specify a stake.", deleteDelay
        )?.asLong ?: return

        val outcome = scratchService.scratch(requestingUserDto.discordId, guild.idLong, stake)
        WagerCommandEmbeds.reply(event, embedFor(outcome), deleteDelay)
    }

    private fun cellsLine(cells: List<SlotMachine.Symbol>): String =
        cells.joinToString(separator = " ") { it.display }

    private fun embedFor(outcome: ScratchOutcome) = when (outcome) {
        is ScratchOutcome.Win -> EmbedBuilder()
            .setTitle("🎟️ ${cellsLine(outcome.cells)}")
            .setDescription(
                "**${outcome.matchCount}× ${outcome.winningSymbol.display}** &mdash; " +
                    "won **+${outcome.net} credits**."
            )
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.WIN)
            .build()

        is ScratchOutcome.Lose -> EmbedBuilder()
            .setTitle("🎟️ ${cellsLine(outcome.cells)}")
            .setDescription("No ${ScratchCard.MATCH_THRESHOLD}-of-a-kind. Lost **${outcome.stake} credits**.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.LOSE)
            .build()

        is ScratchOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is ScratchOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is ScratchOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        ScratchOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }
}
