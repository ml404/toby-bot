package bot.toby.scheduling

import common.logging.DiscordLogger
import database.dto.ConfigDto
import database.service.ConfigService
import database.service.JackpotLotteryService
import database.service.LotteryHelper
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

/**
 * Posts a "yesterday's draw / today's draw" combined summary to a
 * per-guild lottery channel, pinging winners so they actually see the
 * win in their notifications.
 *
 * Channel resolution mirrors [MonthlyLeaderboardJob.resolveChannel]:
 *   1. `LOTTERY_CHANNEL` if set and writable
 *   2. `LEADERBOARD_CHANNEL` fallback (so a guild with one configured
 *      announcement channel doesn't need to set both)
 *   3. `guild.systemChannel` if writable
 *   4. Skip with warn log if none
 *
 * Pings: winners' discord ids appear in the message **content** via
 * [`MessageCreateAction.addContent`] (loud notification) and again in
 * the embed body for visual context (silent there). Non-winning
 * variants (BelowMinBuyers, NoTickets) skip the addContent ping
 * entirely so a buyer-less day stays quiet.
 */
@Component
class LotteryAnnouncer @Autowired constructor(
    private val configService: ConfigService,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    /**
     * Announce one daily-cycle outcome. Posts at most one message; on
     * any send failure logs and moves on (the cycle itself already
     * completed — failure to announce shouldn't roll back payouts).
     */
    fun announceCycle(
        guild: Guild,
        mode: String,
        priorOutcome: PriorOutcome?,
        openOutcome: OpenSummary,
    ) {
        val channel = resolveChannel(guild) ?: run {
            logger.warn(
                "No writable channel for daily lottery announcement in guild ${guild.idLong}; " +
                    "set LOTTERY_CHANNEL or LEADERBOARD_CHANNEL, or grant the bot send-messages " +
                    "in the system channel."
            )
            return
        }
        val embed = buildEmbed(guild, mode, priorOutcome, openOutcome)
        val pingIds = winnerPingIds(priorOutcome)
        runCatching {
            val send = channel.sendMessageEmbeds(embed)
            if (pingIds.isNotEmpty()) {
                send.addContent(pingIds.joinToString(" ") { "<@$it>" })
            }
            send.queue()
        }.onFailure {
            logger.error("Could not post lottery announcement to ${channel.id}: ${it.message}")
        }
    }

    /**
     * Internal-but-public sealed type so [LotteryDailyJob] and
     * [web.service.ModerationWebService] can pass back what happened
     * without leaning on the [JackpotLotteryService] outcome types
     * directly (they carry persistence shapes; this carries display
     * shapes).
     */
    sealed interface PriorOutcome {
        data class MatchDrawn(
            val drawnNumbers: List<Int>,
            val tierPayouts: List<JackpotLotteryService.MatchTierPayout>,
            val totalPaid: Long,
            val rolledBack: Long,
        ) : PriorOutcome

        data class WeightedDrawn(
            val payouts: List<JackpotLotteryService.WinnerPayout>,
            val totalPaid: Long,
            val drained: Long,
        ) : PriorOutcome

        data class BelowMinBuyers(val have: Int, val need: Int) : PriorOutcome

        data object NoTickets : PriorOutcome
    }

    /**
     * Open-side summary. We only need the seeded amount + ticket price
     * for the "today's draw" line; an empty pool / skipped open
     * collapses to a single-flag variant.
     */
    sealed interface OpenSummary {
        data class Ok(val seeded: Long, val ticketPrice: Long, val poolAmount: Long) : OpenSummary
        data object Skipped : OpenSummary
    }

    // ---- channel resolution ----

    private fun resolveChannel(guild: Guild): TextChannel? {
        val bot = guild.selfMember
        // 1. LOTTERY_CHANNEL — explicit lottery channel.
        LotteryHelper.lotteryChannelId(configService, guild.idLong)?.let { id ->
            val channel = guild.getTextChannelById(id)
            if (channel != null && bot.hasPermission(channel, *REQUIRED_PERMS)) return channel
        }
        // 2. LEADERBOARD_CHANNEL — shared announce channel fallback.
        configService.getConfigByName(
            ConfigDto.Configurations.LEADERBOARD_CHANNEL.configValue,
            guild.id
        )?.value?.toLongOrNull()?.let { id ->
            val channel = guild.getTextChannelById(id)
            if (channel != null && bot.hasPermission(channel, *REQUIRED_PERMS)) return channel
        }
        // 3. systemChannel as last resort.
        return guild.systemChannel?.takeIf { bot.hasPermission(it, *REQUIRED_PERMS) }
    }

    // ---- embed building ----

    private fun buildEmbed(
        guild: Guild,
        mode: String,
        priorOutcome: PriorOutcome?,
        openOutcome: OpenSummary,
    ): MessageEmbed {
        val builder = EmbedBuilder()
            .setTitle("🎟️ Daily Lottery — ${guild.name}")
            .setColor(Color(0xF1C40F))

        if (priorOutcome != null) {
            builder.addField(
                "Yesterday's draw",
                renderPriorOutcome(priorOutcome),
                false
            )
        }
        builder.addField("Today's draw", renderOpenSummary(mode, openOutcome), false)
        builder.setFooter(
            when (mode) {
                LotteryHelper.MODE_WEIGHTED -> "Top-3 weighted draw • 50/30/20 share"
                else -> "Pick 5 of 49 • tier payouts 60/25/10/5%"
            }
        )
        return builder.build()
    }

    private fun renderPriorOutcome(prior: PriorOutcome): String = when (prior) {
        is PriorOutcome.MatchDrawn -> {
            val numbers = prior.drawnNumbers.joinToString(" · ")
            val winners = if (prior.tierPayouts.isEmpty()) {
                "No tier matched — rolled back **${prior.rolledBack}** to jackpot."
            } else {
                prior.tierPayouts
                    .sortedByDescending { it.matches }
                    .joinToString("\n") {
                        "${it.matches}/5: <@${it.discordId}> — **${it.share}** credits"
                    }
            }
            "Winning numbers: **$numbers**\n$winners"
        }
        is PriorOutcome.WeightedDrawn -> {
            if (prior.payouts.isEmpty()) {
                "No payouts — pool of **${prior.drained}** rolled back to jackpot."
            } else {
                prior.payouts
                    .mapIndexed { idx, p ->
                        val place = when (idx) { 0 -> "1st"; 1 -> "2nd"; 2 -> "3rd"; else -> "${idx + 1}th" }
                        "$place: <@${p.discordId}> — **${p.amount}** credits"
                    }
                    .joinToString("\n")
            }
        }
        is PriorOutcome.BelowMinBuyers ->
            "Only **${prior.have}** buyer(s) — need **${prior.need}**. Refunded; seed returned to jackpot."
        PriorOutcome.NoTickets ->
            "No tickets bought. Seed returned to jackpot."
    }

    private fun renderOpenSummary(mode: String, open: OpenSummary): String = when (open) {
        is OpenSummary.Ok -> {
            val modeLabel = if (mode == LotteryHelper.MODE_WEIGHTED) "Top-3 weighted" else "Pick 5 of 49"
            "**${open.poolAmount}** credits in the pool · Ticket: **${open.ticketPrice}** · " +
                "Mode: **$modeLabel** · Closes 24h."
        }
        OpenSummary.Skipped ->
            "Today's draw was not opened — see logs / moderation tab."
    }

    private fun winnerPingIds(prior: PriorOutcome?): List<Long> = when (prior) {
        is PriorOutcome.MatchDrawn -> prior.tierPayouts.map { it.discordId }.distinct()
        is PriorOutcome.WeightedDrawn -> prior.payouts.map { it.discordId }.distinct()
        else -> emptyList()
    }

    companion object {
        private val REQUIRED_PERMS = arrayOf(Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
    }
}
