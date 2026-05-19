package bot.toby.modal.modals

import bot.toby.scheduling.LotteryAnnouncer
import common.logging.DiscordLogger
import core.modal.Modal
import core.modal.ModalContext
import database.service.JackpotLotteryService
import database.service.JackpotLotteryService.OpenOutcome
import database.service.LotteryHelper
import org.springframework.stereotype.Component

/**
 * Submission handler for the lottery-open modal opened by
 * `/jackpotadmin lottery_open`. Replaces a four-required-numeric-
 * options slash command (cluttered to type, easy to mis-order in
 * autocomplete) with a single form. All four fields are numeric and
 * validated client-side by Discord (modal `setRequiredRange`); the
 * service still re-validates server-side via [OpenOutcome.InvalidParams].
 *
 * On a successful open, the modal also posts a channel announce via
 * [LotteryAnnouncer.announceCycle] so the lottery surfaces in the
 * configured announce channel (with the Active incentives field, the
 * Buy Tickets action row, and the wide ping per `LOTTERY_PING_MODE`).
 * Without this, admin-fired weighted lotteries were silent on Discord
 * — the open went through but no embed appeared anywhere.
 */
@Component
class JackpotAdminModal(
    private val jackpotLotteryService: JackpotLotteryService,
    private val lotteryAnnouncer: LotteryAnnouncer,
) : Modal {
    override val name = MODAL_NAME

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guild = ctx.guild
        val guildId = guild.idLong

        val ticketPrice = event.getValue(FIELD_TICKET_PRICE)?.asString?.toLongOrNull()
        val duration = event.getValue(FIELD_DURATION_HOURS)?.asString?.toLongOrNull()
        val winners = event.getValue(FIELD_WINNER_COUNT)?.asString?.toIntOrNull()
        val drainPctRaw = event.getValue(FIELD_DRAIN_PCT)?.asString?.toLongOrNull()

        if (ticketPrice == null || duration == null || winners == null || drainPctRaw == null) {
            event.hook.sendMessage("All four fields must be whole numbers.").setEphemeral(true).queue()
            return
        }
        if (winners !in 1..10) {
            event.hook.sendMessage("Winner count must be between 1 and 10.").setEphemeral(true).queue()
            return
        }
        if (drainPctRaw !in 1L..100L) {
            event.hook.sendMessage("Drain percent must be between 1 and 100.").setEphemeral(true).queue()
            return
        }
        val drainPct = drainPctRaw / 100.0

        when (val result = jackpotLotteryService.openLottery(guildId, ticketPrice, duration, winners, drainPct)) {
            is OpenOutcome.Ok -> {
                event.hook.sendMessage(
                    "Opened lottery. Seeded with **${result.seeded}** credits from the jackpot pool. " +
                        "Tickets: **$ticketPrice** credits each. Winners: **$winners**. " +
                        "Closes after **$duration** hours (admin must run `/jackpotadmin lottery_draw`)."
                ).setEphemeral(true).queue()
                // Post the channel announce so members actually see the
                // lottery in Discord — without this the open is silent
                // beyond the ephemeral confirmation to the admin.
                runCatching {
                    lotteryAnnouncer.announceCycle(
                        guild = guild,
                        mode = LotteryHelper.MODE_WEIGHTED,
                        priorOutcome = null,
                        openOutcome = LotteryAnnouncer.OpenSummary.Ok(
                            lotteryId = result.lottery.id,
                            seeded = result.seeded,
                            ticketPrice = result.lottery.ticketPrice,
                            poolAmount = result.lottery.poolAmount,
                        ),
                    )
                }.onFailure {
                    logger.warn(
                        "Admin-fired lottery opened for guild $guildId but announce failed: ${it.message}"
                    )
                }
            }
            OpenOutcome.AlreadyOpen ->
                event.hook.sendMessage("A lottery is already open for this guild. Draw or cancel it first.")
                    .setEphemeral(true).queue()
            OpenOutcome.EmptyPool ->
                event.hook.sendMessage("Jackpot pool is empty — nothing to seed the lottery with.")
                    .setEphemeral(true).queue()
            is OpenOutcome.InvalidParams ->
                event.hook.sendMessage("Invalid params: ${result.reason}.").setEphemeral(true).queue()
        }
    }

    companion object {
        const val MODAL_NAME = "jackpot_admin_lottery_open"
        const val FIELD_TICKET_PRICE = "ticket_price"
        const val FIELD_DURATION_HOURS = "duration_hours"
        const val FIELD_WINNER_COUNT = "winner_count"
        const val FIELD_DRAIN_PCT = "drain_pct"
    }
}
