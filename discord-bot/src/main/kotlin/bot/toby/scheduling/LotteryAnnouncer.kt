package bot.toby.scheduling

import common.logging.DiscordLogger
import database.dto.ConfigDto
import database.dto.JackpotLotteryDto
import database.service.ConfigService
import database.service.JackpotLotteryService
import database.service.LotteryHelper
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.requests.ErrorResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
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
 * Message content layout (each line is optional except the call-to-action):
 *   ```
 *   @everyone | @here | (none)   ← LOTTERY_PING_MODE per guild
 *   🎟️ A new draw is open — buy with </lottery buy:ID>
 *   Congrats: <@winner1> <@winner2>            ← only when there are winners
 *   ```
 *
 * The wide ping defaults to `@everyone` so a fresh-install guild gets
 * the loudest possible nudge to actually buy tickets; admins dial it
 * down via the `LOTTERY_PING_MODE` config (see [LotteryHelper.lotteryPingMode]).
 * The slash-command mention is a clickable shortcut into `/lottery buy`;
 * if JDA can't resolve the command id (cold start, retrieval failure)
 * we fall back to a plain `` `/lottery buy` `` so the embed still ships.
 *
 * `setAllowedMentions` is set explicitly on every send: USER is always
 * allowed (so winners get pinged), and EVERYONE is added only when the
 * content actually contains `@everyone`/`@here`. Without that, Discord
 * silently strips the wide ping.
 */
@Component
class LotteryAnnouncer @Autowired constructor(
    private val configService: ConfigService,
    private val jackpotLotteryService: JackpotLotteryService,
    @Value("\${app.base-url:}") private val webBaseUrl: String = "",
) {

    /**
     * Lazily-resolved id of the global `lottery` slash command. Cached
     * for process lifetime — command ids only change on a fresh
     * `updateCommands` call (i.e. another process restart). Volatile
     * so a happens-before relationship is established between the
     * resolver thread and any later announce-thread reader.
     */
    @Volatile private var cachedLotteryCommandId: Long? = null
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
        val content = buildAnnouncementContent(guild, priorOutcome)
        val lotteryId = (openOutcome as? OpenSummary.Ok)?.lotteryId
        val poolAmount = (openOutcome as? OpenSummary.Ok)?.poolAmount
        val actionRow = announcementActionRow(mode, guild.idLong)
        runCatching {
            val send = channel.sendMessageEmbeds(embed)
                .setAllowedMentions(allowedMentionTypes(content))
            if (content.isNotBlank()) {
                send.addContent(content)
            }
            if (actionRow != null) {
                send.addComponents(actionRow)
            }
            send.queue({ message ->
                if (lotteryId != null && poolAmount != null) {
                    runCatching {
                        jackpotLotteryService.recordAnnouncement(
                            lotteryId = lotteryId,
                            channelId = channel.idLong,
                            messageId = message.idLong,
                            pool = poolAmount,
                        )
                    }.onFailure {
                        logger.warn(
                            "Could not persist lottery announcement reference for " +
                                "lottery $lotteryId (guild ${guild.idLong}): ${it.message}"
                        )
                    }
                }
            }, { failure ->
                logger.error("Could not post lottery announcement to ${channel.id}: ${failure.message}")
            })
        }.onFailure {
            logger.error("Could not post lottery announcement to ${channel.id}: ${it.message}")
        }
    }

    /**
     * Edit the previously-posted announcement embed when [lottery]'s
     * pool has grown since the last announce/refresh tick. Called by
     * [LotteryRefreshJob]. Short-circuits when there's nothing to edit
     * (no message id stored, pool unchanged). On `UNKNOWN_MESSAGE` the
     * announcement reference is cleared so future ticks don't retry.
     *
     * Edits the "Today's draw" field of the existing embed in place;
     * any other fields (e.g. yesterday's recap) are preserved as the
     * mod team last saw them. Embed layout / footer / colour stay
     * identical so the message doesn't visibly flicker on edit.
     */
    fun refreshAnnouncement(guild: Guild, lottery: JackpotLotteryDto) {
        val lotteryId = lottery.id ?: return
        val messageId = lottery.announcementMessageId ?: return
        val channelId = lottery.announcementChannelId ?: return
        val announcedPool = lottery.announcedPoolAmount
        if (announcedPool == lottery.poolAmount) return

        val channel = guild.getTextChannelById(channelId) ?: run {
            logger.warn(
                "Lottery announcement channel $channelId vanished for guild ${guild.idLong}; " +
                    "clearing announcement ref."
            )
            runCatching { jackpotLotteryService.clearAnnouncement(lotteryId) }
            return
        }

        channel.retrieveMessageById(messageId).queue({ existing ->
            val previous = existing.embeds.firstOrNull()
            if (previous == null) {
                logger.warn("Lottery announcement message $messageId has no embeds; clearing ref.")
                runCatching { jackpotLotteryService.clearAnnouncement(lotteryId) }
                return@queue
            }
            val rebuilt = rebuildWithUpdatedTodaysDraw(previous, lottery)
            val actionRow = announcementActionRow(lottery.mode, guild.idLong)
            existing.editMessageEmbeds(rebuilt)
                .setComponents(listOfNotNull(actionRow))
                .queue({
                runCatching { jackpotLotteryService.updateAnnouncedPool(lotteryId, lottery.poolAmount) }
                    .onFailure {
                        logger.warn(
                            "Edited lottery announcement $messageId but failed to persist new " +
                                "pool watermark: ${it.message}"
                        )
                    }
            }, { failure ->
                handleRefreshFailure(failure, lotteryId, messageId, guild.idLong)
            })
        }, { failure ->
            handleRefreshFailure(failure, lotteryId, messageId, guild.idLong)
        })
    }

    private fun handleRefreshFailure(failure: Throwable, lotteryId: Long, messageId: Long, guildId: Long) {
        if (failure is ErrorResponseException && failure.errorResponse == ErrorResponse.UNKNOWN_MESSAGE) {
            logger.info {
                "Lottery announcement $messageId in guild $guildId was deleted; clearing ref."
            }
            runCatching { jackpotLotteryService.clearAnnouncement(lotteryId) }
            return
        }
        logger.warn(
            "Could not refresh lottery announcement $messageId in guild $guildId: ${failure.message}"
        )
    }

    /**
     * Re-render the "Today's draw" field on [previous] with values from
     * the live [lottery] row. Other fields and the embed chrome
     * (title / colour / footer) are preserved verbatim — the only thing
     * that should drift between announce and refresh is the pool value.
     */
    private fun rebuildWithUpdatedTodaysDraw(
        previous: MessageEmbed,
        lottery: JackpotLotteryDto,
    ): MessageEmbed {
        val builder = EmbedBuilder(previous).clearFields()
        val freshSummary = OpenSummary.Ok(
            lotteryId = lottery.id,
            seeded = 0L,                         // unused in renderOpenSummary
            ticketPrice = lottery.ticketPrice,
            poolAmount = lottery.poolAmount,
        )
        // [renderOpenSummary] expects the runtime mode string from
        // [LotteryHelper] (e.g. "WEIGHTED"), but [lottery.mode] holds
        // the DTO column value ("TICKET_WEIGHTED" / "NUMBER_MATCH").
        // Translate explicitly so a refreshed weighted draw doesn't
        // fall through to the "Pick 5 of 49" else branch.
        val runtimeMode = when (lottery.mode) {
            JackpotLotteryDto.MODE_TICKET_WEIGHTED -> LotteryHelper.MODE_WEIGHTED
            else -> LotteryHelper.MODE_NUMBER_MATCH
        }
        val freshTodayBody = renderOpenSummary(runtimeMode, freshSummary)
        previous.fields.forEach { field ->
            if (field.name == TODAYS_DRAW_FIELD) {
                builder.addField(TODAYS_DRAW_FIELD, freshTodayBody, false)
            } else {
                builder.addField(field)
            }
        }
        return builder.build()
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
        /**
         * [lotteryId] is the row id of the OPEN lottery just opened. Used
         * by [announceCycle] to call [JackpotLotteryService.recordAnnouncement]
         * after the embed ships, so [LotteryRefreshJob] can later edit
         * the same message when the prize pool grows. Nullable for
         * cycles where the lottery couldn't be persisted (defensive —
         * the daily job populates it from the open call's `lottery.id`).
         */
        data class Ok(
            val lotteryId: Long? = null,
            val seeded: Long,
            val ticketPrice: Long,
            val poolAmount: Long,
        ) : OpenSummary
        data object Skipped : OpenSummary
    }

    // ---- action row ----

    private fun announcementActionRow(mode: String, guildId: Long): ActionRow? = when (mode) {
        LotteryHelper.MODE_WEIGHTED -> ActionRow.of(
            Button.primary("lottery_buy", "🎫 Buy Tickets")
        )
        else -> webBaseUrl.takeIf { it.isNotBlank() }?.let { base ->
            ActionRow.of(Button.link("$base/casino/$guildId/lottery", "🎯 Pick Numbers"))
        }
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
                YESTERDAYS_DRAW_FIELD,
                renderPriorOutcome(priorOutcome),
                false
            )
        }
        builder.addField(TODAYS_DRAW_FIELD, renderOpenSummary(mode, openOutcome), false)
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

    // ---- announcement content (wide ping + buy CTA + winner pings) ----

    /**
     * Assemble the message content posted alongside the embed. Always
     * includes a call-to-action with a clickable `/lottery buy` mention;
     * optionally prefixes a wide ping per [LotteryHelper.lotteryPingMode];
     * appends per-winner pings on a third line when the prior outcome had
     * winners. Newline-separated so Discord renders each on its own row.
     */
    private fun buildAnnouncementContent(guild: Guild, prior: PriorOutcome?): String {
        val lines = mutableListOf<String>()
        val cta = "🎟️ A new daily lottery is open — buy in with ${slashBuyMention(guild.jda)}!"
        val pingPrefix = widePingPrefix(guild)
        lines += if (pingPrefix != null) "$pingPrefix $cta" else cta
        val winners = winnerPingIds(prior)
        if (winners.isNotEmpty()) {
            lines += "Congrats: " + winners.joinToString(" ") { "<@$it>" }
        }
        return lines.joinToString("\n")
    }

    /** `@everyone` / `@here` / `null`, per the guild's `LOTTERY_PING_MODE`. */
    private fun widePingPrefix(guild: Guild): String? =
        when (LotteryHelper.lotteryPingMode(configService, guild.idLong)) {
            LotteryHelper.PING_HERE -> "@here"
            LotteryHelper.PING_EVERYONE -> "@everyone"
            else -> null  // PING_OFF
        }

    /**
     * Render a clickable `</lottery buy:ID>` slash-command mention, or
     * fall back to plain `` `/lottery buy` `` when the id is unresolvable
     * (cold start, retrieval failure). Falling back keeps the embed
     * shipping rather than failing the whole announce.
     */
    private fun slashBuyMention(jda: JDA): String {
        val id = lotteryBuyCommandId(jda)
        return if (id != null) "</$LOTTERY_COMMAND_NAME $LOTTERY_BUY_SUBCOMMAND:$id>" else "`/$LOTTERY_COMMAND_NAME $LOTTERY_BUY_SUBCOMMAND`"
    }

    private fun lotteryBuyCommandId(jda: JDA): Long? {
        cachedLotteryCommandId?.let { return it }
        val resolved = runCatching {
            jda.retrieveCommands().complete()
                .firstOrNull { it.name == LOTTERY_COMMAND_NAME }
                ?.idLong
        }.onFailure {
            logger.warn("Could not resolve /$LOTTERY_COMMAND_NAME slash command id: ${it.message}")
        }.getOrNull()
        if (resolved != null) cachedLotteryCommandId = resolved
        return resolved
    }

    /**
     * Always allow USER mentions (winner pings); only allow EVERYONE
     * (which covers both `@everyone` and `@here` in JDA) when the
     * content actually contains one. JDA's default would silently strip
     * `@everyone`/`@here` from `addContent`, so omitting this call would
     * make `LOTTERY_PING_MODE` a no-op.
     */
    private fun allowedMentionTypes(content: String): Set<Message.MentionType> {
        val types = mutableSetOf(Message.MentionType.USER)
        if (content.contains("@everyone") || content.contains("@here")) {
            types += Message.MentionType.EVERYONE
        }
        return types
    }

    companion object {
        private val REQUIRED_PERMS = arrayOf(Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
        private const val LOTTERY_COMMAND_NAME = "lottery"
        private const val LOTTERY_BUY_SUBCOMMAND = "buy"

        // Embed field titles. The refresh job swaps in a fresh
        // [TODAYS_DRAW_FIELD] body when the pool grows, leaving the
        // yesterday-recap untouched, so these need to round-trip
        // exactly with what [buildEmbed] writes.
        const val TODAYS_DRAW_FIELD = "Today's draw"
        const val YESTERDAYS_DRAW_FIELD = "Yesterday's draw"
    }
}
