package bot.toby.command.commands.game.lottery

import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import database.service.guild.ConfigService
import database.service.lottery.JackpotLotteryService
import database.service.lottery.JackpotLotteryService.BuyOutcome
import database.service.lottery.LotteryHelper
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import bot.toby.command.commands.game.pvp.GameCommand

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
    private val configService: ConfigService,
) : GameCommand {

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
            is BuyOutcome.Ok -> event.hook.replyAndDelete(
                buildBuyReply(count, r, guildId),
                deleteDelay,
            )
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
        val lottery = jackpotLotteryService.getOpenWeighted(guildId) ?: run {
            event.hook.replyAndDelete("No lottery is open right now.", deleteDelay)
            return
        }
        val tickets = jackpotLotteryService.ticketsForOpenWeighted(guildId)
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

        val mineCount = (mine?.ticketCount ?: 0).toLong()
        val mineBonus = mine?.bonusTickets ?: 0L
        val ownedLine = if (mineBonus > 0L) "**$mineCount** paid + **$mineBonus** bonus"
        else "**$mineCount**"

        event.hook.replyAndDelete(
            "**Lottery status**\n" +
                "Prize pool: **${lottery.poolAmount}** credits\n" +
                "Ticket price: **${lottery.ticketPrice}** credits each\n" +
                "Tickets sold: **$totalTickets** (across ${tickets.size} buyers)\n" +
                "Winners: **${lottery.winnerCount}**\n" +
                "Your tickets: $ownedLine\n" +
                "Closes at: $remainingLabel\n\n" +
                "Top holders:\n$top" +
                renderIncentivesBlock(guildId, mineCount, totalTickets, lottery.milestonesFired),
            deleteDelay,
        )
    }

    /**
     * Render the `/lottery buy` reply with bonus and milestone callouts
     * appended only when they actually fired — a baseline buy reads
     * exactly like the pre-incentive copy.
     */
    private fun buildBuyReply(count: Int, r: BuyOutcome.Ok, guildId: Long): String {
        val lines = mutableListOf<String>()
        val totalOwned = if (r.totalBonusTickets > 0L)
            "**${r.ticketCount}** paid + **${r.totalBonusTickets}** bonus tickets"
        else "**${r.ticketCount}** tickets"
        lines += "Bought **$count** tickets. You now hold $totalOwned " +
            "(spent ${r.totalSpent} credits total). Prize pool: **${r.newPool}** credits. " +
            "Your balance: **${r.newBalance}** credits."
        if (r.bonusTicketsGranted > 0L) {
            lines += "🎁 Bulk-buy bonus: **+${r.bonusTicketsGranted}** free tickets credited."
        }
        if (r.milestoneBonuses.isNotEmpty()) {
            val parts = r.milestoneBonuses.joinToString(", ") {
                "**${it.threshold}** tickets sold → **+${it.creditsAdded}** credits"
            }
            lines += "🚀 Milestone reached! Jackpot top-up: $parts."
        }
        return lines.joinToString("\n")
    }

    /**
     * Append a compact "Active incentives" / "Next thresholds" block to
     * `/lottery status`. Read straight off [LotteryHelper] so the bot
     * surface matches the web "Active rules" summary and the announcer
     * embed without anything in between.
     */
    private fun renderIncentivesBlock(
        guildId: Long,
        userTickets: Long,
        totalTickets: Long,
        milestonesFired: Long,
    ): String {
        val bulk = LotteryHelper.bulkBonusTiers(configService, guildId)
        val mult = LotteryHelper.volumeMultiplierTiers(configService, guildId)
        val milestones = LotteryHelper.poolMilestones(configService, guildId)
        if (bulk.isEmpty() && mult.isEmpty() && milestones.isEmpty()) return ""

        val sb = StringBuilder("\n\n**Active incentives**")
        if (bulk.isNotEmpty()) {
            sb.append("\n• Bulk bonus: ")
            sb.append(bulk.joinToString(", ") { (buy, bonus) -> "buy ≥$buy → +$bonus free" })
            val nextBulk = bulk.firstOrNull { it.first > userTickets }
            if (nextBulk != null) {
                val gap = nextBulk.first - userTickets
                sb.append(" _(next: buy $gap more in one purchase for +${nextBulk.second})_")
            }
        }
        if (mult.isNotEmpty()) {
            sb.append("\n• Volume multiplier: ")
            sb.append(mult.joinToString(", ") { (total, bp) ->
                "hold ≥$total → ${"%.2f".format(bp / 10000.0)}×"
            })
            val nextMult = mult.firstOrNull { it.first > userTickets }
            if (nextMult != null) {
                val gap = nextMult.first - userTickets
                sb.append(" _(hold ${gap} more to reach ${"%.2f".format(nextMult.second / 10000.0)}×)_")
            }
        }
        if (milestones.isNotEmpty()) {
            sb.append("\n• Pool milestones: ")
            sb.append(milestones.joinToString(", ") { (tickets, pct) -> "@$tickets tickets → +$pct% of jackpot" })
            val nextMilestone = milestones.firstOrNull {
                it.first > milestonesFired && it.first > totalTickets
            }
            if (nextMilestone != null) {
                val gap = nextMilestone.first - totalTickets
                sb.append(" _(next fires in $gap more tickets sold)_")
            }
        }
        return sb.toString()
    }

    private fun replyError(event: SlashCommandInteractionEvent, message: String, deleteDelay: Int) {
        event.hook.replyEphemeralAndDelete(message, deleteDelay)
    }

    companion object {
        private const val SUB_BUY = "buy"
        private const val SUB_STATUS = "status"
        private const val OPT_COUNT = "count"
        private const val MAX_TICKETS_PER_BUY = 1_000L
    }
}
