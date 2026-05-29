package bot.toby.modal.modals

import core.modal.Modal
import core.modal.ModalContext
import database.service.lottery.JackpotLotteryService
import database.service.lottery.JackpotLotteryService.BuyOutcome
import org.springframework.stereotype.Component

@Component
class LotteryBuyModal(
    private val jackpotLotteryService: JackpotLotteryService,
) : Modal {
    override val name = "lottery_buy"

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guildId = ctx.guild.idLong
        val userId = event.user.idLong

        val count = event.getValue("count")?.asString?.toIntOrNull() ?: run {
            event.hook.sendMessage("Enter a valid number.").setEphemeral(true).queue()
            return
        }

        when (val r = jackpotLotteryService.buyTickets(guildId, userId, count)) {
            is BuyOutcome.Ok -> event.hook.sendMessage(buildString {
                append("Bought **$count** ticket(s). ")
                if (r.bonusTicketsGranted > 0) {
                    append("🎁 Plus **${r.bonusTicketsGranted}** bonus ticket(s) from bulk-buy! ")
                }
                append(
                    "You now hold **${r.ticketCount}** tickets " +
                        "(spent ${r.totalSpent} credits total). Prize pool: **${r.newPool}** credits. " +
                        "Your balance: **${r.newBalance}** credits."
                )
                r.milestoneBonuses.forEach { m ->
                    append(
                        "\n🚀 Milestone reached at **${m.threshold}** tickets sold → " +
                            "**+${m.creditsAdded}** credits drawn into the prize pool!"
                    )
                }
            }).setEphemeral(true).queue()

            BuyOutcome.NoOpenLottery ->
                event.hook.sendMessage("No lottery is open right now.").setEphemeral(true).queue()

            is BuyOutcome.InvalidCount ->
                event.hook.sendMessage("Ticket count must be between 1 and 1,000.").setEphemeral(true).queue()

            is BuyOutcome.Insufficient ->
                event.hook.sendMessage("You only have **${r.have}** credits, need **${r.need}**.").setEphemeral(true).queue()

            BuyOutcome.UnknownUser ->
                event.hook.sendMessage("No user record yet. Try another TobyBot command first.").setEphemeral(true).queue()
        }
    }
}
