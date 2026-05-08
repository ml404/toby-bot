package bot.toby.command.commands.economy

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.service.JackpotLotteryService
import database.service.JackpotLotteryService.BuyOutcome
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * `/lottery buy|status` — players' surface for the per-guild jackpot
 * lottery event opened by `/jackpotadmin lottery_open`.
 *
 *   - `buy <count>` debits `count × ticket_price` from the player and
 *     adds it to the prize pool (so the prize grows with engagement).
 *     Each ticket is a weight in the eventual draw.
 *   - `status` shows the open lottery's prize pool, the player's own
 *     ticket count, the top 5 holders, and time-until-close.
 *
 * The actual draw is admin-only (`/jackpotadmin lottery_draw`) so
 * timing is up to whoever opened the event. There's no auto-close on
 * `closes_at` — the timestamp is informational and used only for the
 * "X hours remaining" hint.
 */
@Component
class LotteryCommand @Autowired constructor(
    private val jackpotLotteryService: JackpotLotteryService,
) : EconomyCommand {

    override val name: String = "lottery"
    override val description: String = "Buy tickets in the active jackpot lottery, or check its status."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_BUY, "Buy tickets in the open lottery.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_COUNT, "Number of tickets to buy", true)
                    .setMinValue(1L)
                    .setMaxValue(MAX_TICKETS_PER_BUY),
            ),
        SubcommandData(SUB_STATUS, "Show the open lottery (prize pool, your tickets, top holders)."),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: run {
            replyError(event, "Server only.", deleteDelay); return
        }

        when (event.subcommandName) {
            SUB_BUY -> handleBuy(event, requestingUserDto, guild.idLong, deleteDelay)
            SUB_STATUS -> handleStatus(event, requestingUserDto, guild.idLong, deleteDelay)
            else -> replyError(event, "Pick a subcommand: $SUB_BUY / $SUB_STATUS.", deleteDelay)
        }
    }

    private fun handleBuy(
        event: SlashCommandInteractionEvent,
        user: UserDto,
        guildId: Long,
        deleteDelay: Int,
    ) {
        val count = event.getOption(OPT_COUNT)?.asLong?.toInt() ?: run {
            replyError(event, "Specify a ticket count.", deleteDelay); return
        }
        when (val r = jackpotLotteryService.buyTickets(guildId, user.discordId, count)) {
            is BuyOutcome.Ok -> event.hook.sendMessage(
                "Bought **$count** tickets. You now hold **${r.ticketCount}** tickets " +
                    "(spent ${r.totalSpent} credits total). Prize pool: **${r.newPool}** credits. " +
                    "Your balance: **${r.newBalance}** credits."
            ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            BuyOutcome.NoOpenLottery ->
                replyError(event, "No lottery is open right now.", deleteDelay)
            is BuyOutcome.InvalidCount ->
                replyError(event, "Ticket count must be positive.", deleteDelay)
            is BuyOutcome.Insufficient ->
                replyError(event, "You only have **${r.have}** credits, need **${r.need}**.", deleteDelay)
            BuyOutcome.UnknownUser ->
                replyError(event, "No user record yet. Try another TobyBot command first.", deleteDelay)
        }
    }

    private fun handleStatus(
        event: SlashCommandInteractionEvent,
        user: UserDto,
        guildId: Long,
        deleteDelay: Int,
    ) {
        val lottery = jackpotLotteryService.getOpen(guildId) ?: run {
            event.hook.sendMessage("No lottery is open right now.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        val tickets = jackpotLotteryService.ticketsForOpen(guildId)
        val totalTickets = tickets.sumOf { it.ticketCount.toLong() }
        val mine = tickets.firstOrNull { it.discordId == user.discordId }
        val top = tickets.sortedByDescending { it.ticketCount }.take(5)
            .joinToString("\n") { "<@${it.discordId}> — ${it.ticketCount} tickets" }
            .ifEmpty { "_no buyers yet_" }

        val remaining = Duration.between(Instant.now(), lottery.closesAt)
        val remainingLabel = when {
            remaining.isNegative -> "(past closes_at — admin must draw or cancel)"
            remaining.toHours() >= 1 -> "${remaining.toHours()}h remaining (informational)"
            else -> "${remaining.toMinutes().coerceAtLeast(0)}m remaining (informational)"
        }

        event.hook.sendMessage(
            "**Lottery status**\n" +
                "Prize pool: **${lottery.poolAmount}** credits\n" +
                "Ticket price: **${lottery.ticketPrice}** credits each\n" +
                "Tickets sold: **$totalTickets** (across ${tickets.size} buyers)\n" +
                "Winners: **${lottery.winnerCount}**\n" +
                "Your tickets: **${mine?.ticketCount ?: 0}**\n" +
                "Closes at: $remainingLabel\n\n" +
                "Top holders:\n$top"
        ).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun replyError(event: SlashCommandInteractionEvent, message: String, deleteDelay: Int) {
        event.hook.sendMessage(message).setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    companion object {
        private const val SUB_BUY = "buy"
        private const val SUB_STATUS = "status"
        private const val OPT_COUNT = "count"
        private const val MAX_TICKETS_PER_BUY = 1_000L
    }
}
