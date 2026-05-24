package bot.toby.command.commands.moderation

import bot.toby.modal.modals.JackpotAdminModal
import bot.toby.scheduling.LotteryAnnouncer
import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import database.service.casino.CasinoAdminService
import database.service.lottery.JackpotLotteryService
import database.service.economy.JackpotService
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.modals.Modal
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
    private val lotteryAnnouncer: LotteryAnnouncer,
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
        SubcommandData(SUB_LOTTERY_OPEN, "Open a multi-winner ticketed lottery seeded from the jackpot pool (form)."),
        SubcommandData(SUB_LOTTERY_DRAW, "Close the open lottery and pay weighted winners."),
        SubcommandData(SUB_LOTTERY_CANCEL, "Cancel the open lottery (refund tickets, return seed to pool)."),
        SubcommandData(
            SUB_LOTTERY_REFRESH_EMBED,
            "Re-render announce embeds for any open lottery — pick up tier edits without the 5-min wait.",
        ),
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        // The `lottery_open` subcommand opens a modal — must be the first
        // response on the interaction, before any deferReply. All other
        // subcommands defer-and-reply through `event.hook`.
        if (event.subcommandName == SUB_LOTTERY_OPEN) {
            val guildPresent = event.guild != null
            val authorised = isAuthorised(ctx, requestingUserDto)
            if (!guildPresent || !authorised) {
                val message = if (!guildPresent) "Guild only." else "Server owner or superuser only."
                event.reply(message).setEphemeral(true).queue()
                return
            }
            event.replyModal(buildLotteryOpenModal()).queue()
            return
        }

        event.deferReply(true).queue()
        val guildId = event.guild?.idLong ?: run {
            event.hook.replyEphemeralAndDelete("Guild only.", deleteDelay)
            return
        }
        if (!isAuthorised(ctx, requestingUserDto)) {
            event.hook.replyEphemeralAndDelete("Server owner or superuser only.", deleteDelay)
            return
        }
        when (event.subcommandName) {
            SUB_RESET -> handleReset(event, guildId, deleteDelay)
            SUB_REFUND -> handleRefund(event, guildId, deleteDelay)
            SUB_POOL -> handlePool(event, guildId, deleteDelay)
            SUB_LOTTERY_DRAW -> handleLotteryDraw(event, guildId, deleteDelay)
            SUB_LOTTERY_CANCEL -> handleLotteryCancel(event, guildId, deleteDelay)
            SUB_LOTTERY_REFRESH_EMBED -> handleLotteryRefreshEmbed(event, guildId, deleteDelay)
            else -> event.hook.replyEphemeralAndDelete(
                "Pick a subcommand: $SUB_RESET / $SUB_REFUND / $SUB_POOL / " +
                    "$SUB_LOTTERY_OPEN / $SUB_LOTTERY_DRAW / $SUB_LOTTERY_CANCEL / " +
                    "$SUB_LOTTERY_REFRESH_EMBED.",
                deleteDelay,
            )
        }
    }

    private fun buildLotteryOpenModal(): Modal {
        val builder = Modal.create(JackpotAdminModal.MODAL_NAME, "Open Lottery Event")
        val ticketPrice = TextInput.create(JackpotAdminModal.FIELD_TICKET_PRICE, TextInputStyle.SHORT)
            .setPlaceholder("100").setRequired(true).setRequiredRange(1, 6).build()
        val duration = TextInput.create(JackpotAdminModal.FIELD_DURATION_HOURS, TextInputStyle.SHORT)
            .setPlaceholder("24").setRequired(true).setRequiredRange(1, 4).build()
        val winners = TextInput.create(JackpotAdminModal.FIELD_WINNER_COUNT, TextInputStyle.SHORT)
            .setPlaceholder("1-10").setRequired(true).setRequiredRange(1, 2).build()
        val drainPct = TextInput.create(JackpotAdminModal.FIELD_DRAIN_PCT, TextInputStyle.SHORT)
            .setPlaceholder("1-100").setRequired(true).setRequiredRange(1, 3).build()
        builder.addComponents(Label.of("Ticket price (credits)", ticketPrice))
        builder.addComponents(Label.of("Duration (hours)", duration))
        builder.addComponents(Label.of("Winner count (1-10)", winners))
        builder.addComponents(Label.of("Drain percent (1-100)", drainPct))
        return builder.build()
    }

    private fun isAuthorised(ctx: CommandContext, requestingUserDto: UserDto): Boolean =
        ctx.member?.isOwner == true || requestingUserDto.superUser == true

    private fun handleReset(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val drained = casinoAdminService.resetJackpot(guildId)
        val message = if (drained == 0L) "Jackpot pool was already empty."
            else "Reset jackpot pool. Drained **$drained** credits."
        event.hook.replyEphemeralAndDelete(message, deleteDelay)
    }

    private fun handleRefund(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val target = event.getOption(OPT_USER)?.asUser ?: run {
            replyError(event, "User option missing.", deleteDelay); return
        }
        val amount = event.getOption(OPT_AMOUNT)?.asLong ?: run {
            replyError(event, "Amount option missing.", deleteDelay); return
        }
        when (val result = casinoAdminService.refundToJackpot(target.idLong, guildId, amount)) {
            is CasinoAdminService.RefundOutcome.Ok -> event.hook.replyEphemeralAndDelete(
                "Refunded **${result.drained}** credits from ${target.asMention} into the jackpot. " +
                    "New pool: **${result.newPool}**. New balance: **${result.newSourceBalance}**.",
                deleteDelay,
            )
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
        event.hook.replyEphemeralAndDelete("Current jackpot pool: **$pool** credits.", deleteDelay)
    }

    private fun handleLotteryDraw(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        when (val result = jackpotLotteryService.drawLottery(guildId)) {
            is JackpotLotteryService.DrawOutcome.Ok -> {
                val lines = result.payouts.mapIndexed { idx, p ->
                    "${idx + 1}. <@${p.discordId}> — **${p.amount}** credits (${p.ticketCount} tickets)"
                }
                val body = if (lines.isEmpty()) "_No winners drawn._" else lines.joinToString("\n")
                val impactBits = mutableListOf<String>()
                if (result.bonusTicketsAwarded > 0L) {
                    impactBits += "🎁 ${result.bonusTicketsAwarded} bulk bonus tickets awarded"
                }
                if (result.highestMilestoneFired > 0L) {
                    impactBits += "🚀 milestone up to ${result.highestMilestoneFired} tickets fired"
                }
                val impactLine = if (impactBits.isEmpty()) "" else "\n${impactBits.joinToString(" · ")}"
                event.hook.replyAndDelete(
                    "Lottery drawn. Total paid: **${result.totalPaid}** of **${result.drained}** credits.\n$body$impactLine",
                    deleteDelay,
                )
            }
            JackpotLotteryService.DrawOutcome.NoOpenLottery ->
                replyError(event, "No open lottery to draw.", deleteDelay)
            JackpotLotteryService.DrawOutcome.NoTickets ->
                replyError(event, "Lottery has no ticket buyers — cancel instead to refund the seed.", deleteDelay)
            is JackpotLotteryService.DrawOutcome.BelowMinBuyers ->
                replyError(
                    event,
                    "Only **${result.have}** distinct buyer(s) — need **${result.need}**. " +
                        "Cancel to refund buyers and return the seed (admins can lower " +
                        "`LOTTERY_DAILY_MIN_BUYERS` if this threshold is too strict).",
                    deleteDelay,
                )
        }
    }

    /**
     * Force-rebuild the announce embed for every open lottery on this
     * guild. Bypasses the 5-minute `LotteryRefreshJob` timer and its
     * pool/digest short-circuit so admins can verify a tier edit
     * landed without waiting (or guessing whether the digest path
     * fired). Each call to [LotteryAnnouncer.refreshAnnouncement]
     * runs asynchronously inside JDA's queue, so the admin's reply is
     * "queued N", not "completed".
     */
    private fun handleLotteryRefreshEmbed(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        val guild = event.guild ?: run {
            replyError(event, "Guild only.", deleteDelay)
            return
        }
        val openLotteries = jackpotLotteryService.getOpenLotteriesForRefresh(guildId)
        if (openLotteries.isEmpty()) {
            event.hook.replyEphemeralAndDelete("No open lottery to refresh.", deleteDelay)
            return
        }
        var queued = 0
        var skipped = 0
        openLotteries.forEach { lottery ->
            // No announcement reference means the embed was never
            // posted (or got cleared by an UNKNOWN_MESSAGE retry).
            // `refreshAnnouncement` would early-exit anyway, but
            // counting up-front gives the admin a cleaner reply than
            // "queued 1, but actually 0 happened".
            if (lottery.announcementMessageId == null || lottery.announcementChannelId == null) {
                skipped++
            } else {
                lotteryAnnouncer.refreshAnnouncement(guild, lottery, force = true)
                queued++
            }
        }
        val message = buildString {
            append("Queued **$queued** embed refresh")
            if (queued != 1) append("es")
            append(".")
            if (skipped > 0) {
                append(" Skipped **$skipped** (no announce posted yet).")
            }
        }
        event.hook.replyEphemeralAndDelete(message, deleteDelay)
    }

    private fun handleLotteryCancel(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int) {
        when (val result = jackpotLotteryService.cancelLottery(guildId)) {
            is JackpotLotteryService.CancelOutcome.Ok -> event.hook.replyEphemeralAndDelete(
                "Cancelled lottery. Refunded **${result.refundedTotal}** credits to **${result.refundedUsers}** users. " +
                    "Returned **${result.returnedToPool}** credits to the jackpot pool.",
                deleteDelay,
            )
            JackpotLotteryService.CancelOutcome.NoOpenLottery ->
                replyError(event, "No open lottery to cancel.", deleteDelay)
        }
    }

    private fun replyError(event: SlashCommandInteractionEvent, message: String, deleteDelay: Int) {
        event.hook.replyEphemeralAndDelete(message, deleteDelay)
    }

    companion object {
        const val SUB_RESET = "reset"
        const val SUB_REFUND = "refund"
        const val SUB_POOL = "pool"
        const val SUB_LOTTERY_OPEN = "lottery_open"
        const val SUB_LOTTERY_DRAW = "lottery_draw"
        const val SUB_LOTTERY_CANCEL = "lottery_cancel"
        const val SUB_LOTTERY_REFRESH_EMBED = "lottery_refresh_embed"
        const val OPT_USER = "user"
        const val OPT_AMOUNT = "amount"
    }
}
