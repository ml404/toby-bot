package bot.toby.command.commands.moderation

import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import database.service.CasinoAdminService
import database.service.JackpotLotteryService
import database.service.JackpotService
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Owner / superuser admin command for jackpot remediation. Mirrors the
 * `/moderation` casino tab in the web UI: drain the per-guild jackpot
 * pool to zero, claw an exploit refund back into it from a player, or
 * convert a runaway pool into a multi-winner ticketed lottery event.
 *
 * Permission gate matches `SetConfigCommand`: guild owner only, with
 * superuser bypass via the existing `requestingUserDto.superUser` flag.
 */
@Component
class JackpotAdminCommand @Autowired constructor(
    private val casinoAdminService: CasinoAdminService,
    private val jackpotService: JackpotService,
    private val jackpotLotteryService: JackpotLotteryService,
) : ModerationCommand {

    override val name: String = "jackpotadmin"
    override val description: String = "Owner-only jackpot remediation (reset pool, refund, lottery event)."

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_RESET, "Drain the jackpot pool to zero."),
        SubcommandData(SUB_REFUND, "Debit a user and deposit the amount into the jackpot pool.")
            .addOptions(
                OptionData(OptionType.USER, OPT_USER, "User to debit", true),
                OptionData(OptionType.INTEGER, OPT_AMOUNT, "Credits to move into the jackpot pool", true)
                    .setMinValue(1L),
            ),
        SubcommandData(SUB_POOL, "Show the current jackpot pool size."),
        SubcommandData(SUB_LOTTERY_OPEN, "Open a multi-winner ticketed lottery seeded from the jackpot pool.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_TICKET_PRICE, "Credits per ticket", true).setMinValue(1L),
                OptionData(OptionType.INTEGER, OPT_DURATION_HOURS, "How many hours the sale stays open", true)
                    .setMinValue(1L),
                OptionData(OptionType.INTEGER, OPT_WINNER_COUNT, "How many winners share the prize", true)
                    .setMinValue(1L)
                    .setMaxValue(10L),
                OptionData(OptionType.INTEGER, OPT_DRAIN_PCT, "Percent of pool to seed the lottery (1-100)", true)
                    .setMinValue(1L)
                    .setMaxValue(100L),
            ),
        SubcommandData(SUB_LOTTERY_DRAW, "Close the open lottery and pay weighted winners."),
        SubcommandData(SUB_LOTTERY_CANCEL, "Cancel the open lottery (refund tickets, return seed to pool)."),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()
        val guildId = event.guild?.idLong ?: run {
            event.hook.sendMessage("Guild only.").setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        if (!isAuthorised(ctx, requestingUserDto)) {
            event.hook.sendMessage("Server owner or superuser only.")
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        when (event.subcommandName) {
            SUB_RESET -> handleReset(event, guildId, deleteDelay)
            SUB_REFUND -> handleRefund(event, guildId, deleteDelay)
            SUB_POOL -> handlePool(event, guildId, deleteDelay)
            SUB_LOTTERY_OPEN -> handleLotteryOpen(event, guildId, deleteDelay)
            SUB_LOTTERY_DRAW -> handleLotteryDraw(event, guildId, deleteDelay)
            SUB_LOTTERY_CANCEL -> handleLotteryCancel(event, guildId, deleteDelay)
            else -> event.hook.sendMessage(
                "Pick a subcommand: $SUB_RESET / $SUB_REFUND / $SUB_POOL / " +
                    "$SUB_LOTTERY_OPEN / $SUB_LOTTERY_DRAW / $SUB_LOTTERY_CANCEL."
            ).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
        }
    }

    private fun isAuthorised(ctx: CommandContext, requestingUserDto: UserDto): Boolean =
        ctx.member?.isOwner == true || requestingUserDto.superUser == true

    private fun handleReset(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val drained = casinoAdminService.resetJackpot(guildId)
        val message = if (drained == 0L) "Jackpot pool was already empty."
            else "Reset jackpot pool. Drained **$drained** credits."
        event.hook.sendMessage(message).setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun handleRefund(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val target = event.getOption(OPT_USER)?.asUser ?: run {
            replyError(event, "User option missing.", deleteDelay); return
        }
        val amount = event.getOption(OPT_AMOUNT)?.asLong ?: run {
            replyError(event, "Amount option missing.", deleteDelay); return
        }
        when (val result = casinoAdminService.refundToJackpot(target.idLong, guildId, amount)) {
            is CasinoAdminService.RefundOutcome.Ok -> event.hook.sendMessage(
                "Refunded **${result.drained}** credits from ${target.asMention} into the jackpot. " +
                    "New pool: **${result.newPool}**. New balance: **${result.newSourceBalance}**."
            ).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            is CasinoAdminService.RefundOutcome.Insufficient ->
                replyError(
                    event,
                    "${target.asMention} has only **${result.have}** credits, can't refund **${result.needed}**.",
                    deleteDelay,
                )
            is CasinoAdminService.RefundOutcome.InvalidAmount ->
                replyError(event, "Amount must be positive.", deleteDelay)
        }
    }

    private fun handlePool(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val pool = jackpotService.getPool(guildId)
        event.hook.sendMessage("Current jackpot pool: **$pool** credits.")
            .setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun handleLotteryOpen(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val ticketPrice = event.getOption(OPT_TICKET_PRICE)?.asLong
            ?: return replyError(event, "Ticket price option missing.", deleteDelay)
        val duration = event.getOption(OPT_DURATION_HOURS)?.asLong
            ?: return replyError(event, "Duration option missing.", deleteDelay)
        val winners = event.getOption(OPT_WINNER_COUNT)?.asLong?.toInt()
            ?: return replyError(event, "Winner count option missing.", deleteDelay)
        val drainPctRaw = event.getOption(OPT_DRAIN_PCT)?.asLong
            ?: return replyError(event, "Drain pct option missing.", deleteDelay)
        val drainPct = drainPctRaw.coerceIn(1L, 100L) / 100.0

        when (val result = jackpotLotteryService.openLottery(
            guildId, ticketPrice, duration, winners, drainPct
        )) {
            is JackpotLotteryService.OpenOutcome.Ok -> event.hook.sendMessage(
                "Opened lottery. Seeded with **${result.seeded}** credits from the jackpot pool. " +
                    "Tickets: **$ticketPrice** credits each. Winners: **$winners**. " +
                    "Closes after **$duration** hours (admin must run `/jackpotadmin lottery_draw`)."
            ).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            JackpotLotteryService.OpenOutcome.AlreadyOpen ->
                replyError(event, "A lottery is already open for this guild. Draw or cancel it first.", deleteDelay)
            JackpotLotteryService.OpenOutcome.EmptyPool ->
                replyError(event, "Jackpot pool is empty — nothing to seed the lottery with.", deleteDelay)
            is JackpotLotteryService.OpenOutcome.InvalidParams ->
                replyError(event, "Invalid params: ${result.reason}.", deleteDelay)
        }
    }

    private fun handleLotteryDraw(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        when (val result = jackpotLotteryService.drawLottery(guildId)) {
            is JackpotLotteryService.DrawOutcome.Ok -> {
                val lines = result.payouts.mapIndexed { idx, p ->
                    "${idx + 1}. <@${p.discordId}> — **${p.amount}** credits (${p.ticketCount} tickets)"
                }
                val body = if (lines.isEmpty()) "_No winners drawn._" else lines.joinToString("\n")
                event.hook.sendMessage(
                    "Lottery drawn. Total paid: **${result.totalPaid}** of **${result.drained}** credits.\n$body"
                ).setEphemeral(false).queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
            JackpotLotteryService.DrawOutcome.NoOpenLottery ->
                replyError(event, "No open lottery to draw.", deleteDelay)
            JackpotLotteryService.DrawOutcome.NoTickets ->
                replyError(event, "Lottery has no ticket buyers — cancel instead to refund the seed.", deleteDelay)
        }
    }

    private fun handleLotteryCancel(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        when (val result = jackpotLotteryService.cancelLottery(guildId)) {
            is JackpotLotteryService.CancelOutcome.Ok -> event.hook.sendMessage(
                "Cancelled lottery. Refunded **${result.refundedTotal}** credits to **${result.refundedUsers}** users. " +
                    "Returned **${result.returnedToPool}** credits to the jackpot pool."
            ).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            JackpotLotteryService.CancelOutcome.NoOpenLottery ->
                replyError(event, "No open lottery to cancel.", deleteDelay)
        }
    }

    private fun replyError(event: SlashCommandInteractionEvent, message: String, deleteDelay: Int) {
        event.hook.sendMessage(message).setEphemeral(true)
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    companion object {
        const val SUB_RESET = "reset"
        const val SUB_REFUND = "refund"
        const val SUB_POOL = "pool"
        const val SUB_LOTTERY_OPEN = "lottery_open"
        const val SUB_LOTTERY_DRAW = "lottery_draw"
        const val SUB_LOTTERY_CANCEL = "lottery_cancel"
        const val OPT_USER = "user"
        const val OPT_AMOUNT = "amount"
        const val OPT_TICKET_PRICE = "ticket_price"
        const val OPT_DURATION_HOURS = "duration_hours"
        const val OPT_WINNER_COUNT = "winner_count"
        const val OPT_DRAIN_PCT = "drain_pct"
    }
}
