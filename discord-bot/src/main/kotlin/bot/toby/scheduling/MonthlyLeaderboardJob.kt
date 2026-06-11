package bot.toby.scheduling

import common.logging.DiscordLogger
import database.dto.guild.ConfigDto
import database.dto.economy.MonthlyCreditSnapshotDto
import database.service.guild.ConfigService
import database.service.economy.MonthlyCreditSnapshotService
import database.service.activity.UbiDailyService
import database.service.user.UserService
import database.service.activity.VoiceSessionService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.awt.Color
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Component
@Profile("prod")
class MonthlyLeaderboardJob @Autowired constructor(
    private val jda: JDA,
    private val userService: UserService,
    private val voiceSessionService: VoiceSessionService,
    private val snapshotService: MonthlyCreditSnapshotService,
    private val configService: ConfigService,
    private val ubiDailyService: UbiDailyService,
    private val hourGate: GuildHourGate,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    companion object {
        const val TOP_N = 10

        /** Fallback UTC hour when MONTHLY_LEADERBOARD_HOUR is unset or invalid. */
        const val DEFAULT_LEADERBOARD_HOUR: Int = 12

        /**
         * The month boundary itself. The hourly tick at this UTC hour (00:00 on
         * the 1st) freezes each guild's authoritative end-of-last-month snapshot,
         * so the figures reflect balances at the moment the new month starts even
         * when a guild posts later in the day.
         */
        const val BOUNDARY_HOUR: Int = 0
    }

    /**
     * Runs hourly on the 1st of the month. Two things happen per guild:
     *
     * 1. At [BOUNDARY_HOUR] (00:00 UTC) — and, as a best-effort fallback, at the
     *    guild's posting hour — the guild's month-boundary snapshot is frozen via
     *    [snapshotService] `upsertIfMissing`. The midnight tick wins the race, so
     *    the frozen value is the balance at exactly the month boundary.
     * 2. When the current UTC hour matches the guild's `MONTHLY_LEADERBOARD_HOUR`
     *    config (default [DEFAULT_LEADERBOARD_HOUR] = 12:00 UTC) the board is
     *    posted, computed purely from the frozen boundary snapshots — never the
     *    live balance.
     *
     * Spring's cron scheduler doesn't replay missed ticks, so the post fires at
     * most once per month per guild.
     */
    @Scheduled(cron = "0 0 * 1 * *", zone = "UTC")
    fun postMonthlyLeaderboard() {
        val now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC))
        val today = now.toLocalDate().withDayOfMonth(1)
        val previousMonthStart = today.minusMonths(1)
        logger.info {
            "Running monthly leaderboard job for month starting $previousMonthStart (hour=${now.hour})"
        }

        jda.guildCache.forEach { guild ->
            runCatching {
                val targetHour = hourGate.configuredHour(
                    guild.idLong,
                    ConfigDto.Configurations.MONTHLY_LEADERBOARD_HOUR,
                    DEFAULT_LEADERBOARD_HOUR,
                )
                val isBoundary = now.hour == BOUNDARY_HOUR
                val isPosting = now.hour == targetHour
                if (!isBoundary && !isPosting) return@runCatching

                val users = userService.listGuildUsers(guild.idLong).filterNotNull()
                if (users.isEmpty()) {
                    logger.info { "Skipping guild ${guild.idLong} — no tracked users." }
                    return@runCatching
                }

                // Freeze (or reuse) the authoritative month-boundary snapshot.
                // upsertIfMissing keeps whatever was written first — the 00:00
                // tick — so a later posting tick reads the midnight values.
                val boundarySnapshots = freezeMonthBoundary(guild.idLong, today, users)

                if (isPosting) {
                    postForGuild(guild, today, previousMonthStart, users, boundarySnapshots)
                }
            }.onFailure { logger.error("Monthly leaderboard failed for guild ${guild.idLong}: ${it.message}") }
        }
    }

    /**
     * Snapshots each user's current balance as the month-boundary value for
     * [boundaryDate], unless one already exists, and returns the authoritative
     * (possibly pre-existing) snapshot per user. The live balance is only ever
     * used to seed a missing row — once frozen, the boundary value is reused.
     */
    private fun freezeMonthBoundary(
        guildId: Long,
        boundaryDate: LocalDate,
        users: List<database.dto.user.UserDto>
    ): Map<Long, MonthlyCreditSnapshotDto> = users.associate { dto ->
        dto.discordId to snapshotService.upsertIfMissing(
            MonthlyCreditSnapshotDto(
                discordId = dto.discordId,
                guildId = guildId,
                snapshotDate = boundaryDate,
                socialCredit = dto.socialCredit ?: 0L,
                tobyCoins = dto.tobyCoins
            )
        )
    }

    private fun postForGuild(
        guild: Guild,
        thisMonthStart: LocalDate,
        previousMonthStart: LocalDate,
        users: List<database.dto.user.UserDto>,
        boundarySnapshots: Map<Long, MonthlyCreditSnapshotDto>
    ) {
        val priorSnapshots = snapshotService.listForGuildDate(guild.idLong, previousMonthStart)
            .associateBy { it.discordId }

        val previousMonthRange = previousMonthStart.atStartOfDay().toInstant(ZoneOffset.UTC) to
                thisMonthStart.atStartOfDay().toInstant(ZoneOffset.UTC)
        val voiceSecondsByUser = voiceSessionService.sumCountedSecondsInRangeByUser(
            guild.idLong,
            previousMonthRange.first,
            previousMonthRange.second
        )
        // UBI grants land in socialCredit but are a fixed handout, not earned
        // activity — subtract them so the leaderboard reflects voice + command
        // + intro + trade earnings only.
        val ubiByUser = ubiDailyService.sumGrantedInRangeByUser(
            guild.idLong, previousMonthStart, thisMonthStart
        )

        val rows = users
            .filter { dto -> guild.getMemberById(dto.discordId)?.user?.isBot != true }
            .map { dto ->
                // End-of-last-month total = the balance frozen at this month's
                // boundary (00:00 on the 1st). Fall back to the live balance only
                // if the boundary freeze somehow never ran for this user.
                val endTotal = boundarySnapshots[dto.discordId]?.socialCredit ?: (dto.socialCredit ?: 0L)
                // Start-of-last-month total = the previous month's boundary snapshot.
                val startTotal = priorSnapshots[dto.discordId]?.socialCredit
                // No prior-month snapshot means we have no starting point to
                // measure last month's change against, so report 0 earned rather
                // than counting the user's whole balance. Mirrors the web
                // leaderboards; the boundary snapshot frozen above seeds the
                // baseline so next month's earnings are correct.
                val ubi = ubiByUser[dto.discordId] ?: 0L
                val creditsEarned = if (startTotal == null) 0L
                    else (endTotal - startTotal - ubi).coerceAtLeast(0L)
                MonthlyRow(
                    discordId = dto.discordId,
                    creditsEarned = creditsEarned,
                    endTotal = endTotal,
                    voiceSeconds = voiceSecondsByUser[dto.discordId] ?: 0L
                )
            }
            .filter { it.creditsEarned > 0 || it.voiceSeconds > 0 }
            .sortedWith(
                compareByDescending<MonthlyRow> { it.creditsEarned }
                    .thenByDescending { it.endTotal }
                    .thenByDescending { it.voiceSeconds }
            ).take(TOP_N)

        val channel = resolveChannel(guild)
        if (channel == null) {
            logger.warn(
                "No channel to post monthly leaderboard in guild ${guild.idLong}. " +
                        "Set one via /setleaderboardchannel or grant the bot a writable system channel."
            )
        } else {
            val embed = buildEmbed(guild, previousMonthStart, rows)
            runCatching { channel.sendMessageEmbeds(embed).queue() }
                .onFailure { logger.error("Could not send leaderboard to ${channel.id}: ${it.message}") }
        }
    }

    private fun resolveChannel(guild: Guild): TextChannel? {
        val bot = guild.selfMember
        val configured = configService.getConfigByName(
            ConfigDto.Configurations.LEADERBOARD_CHANNEL.configValue,
            guild.id
        )?.value?.toLongOrNull()?.let { guild.getTextChannelById(it) }
        val candidates = listOfNotNull(configured, guild.systemChannel)
        candidates.firstOrNull {
            bot.hasPermission(it, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
        }?.let { return it }
        // Same contract as NotificationRouter.resolveChannel: when no
        // candidate passes the permission check but one exists, attempt
        // the send there rather than dropping the post — a computed-
        // permission false negative degrades to a logged send failure
        // instead of a silent drop.
        return candidates.firstOrNull()?.also {
            logger.warn(
                "No leaderboard channel for guild ${guild.idLong} passes the send/embed permission check; " +
                    "attempting best-effort post to #${it.name} anyway."
            )
        }
    }

    private fun buildEmbed(guild: Guild, previousMonthStart: LocalDate, rows: List<MonthlyRow>): net.dv8tion.jda.api.entities.MessageEmbed {
        val monthLabel = previousMonthStart.month.name.lowercase().replaceFirstChar { it.titlecase() } +
                " ${previousMonthStart.year}"

        val description = if (rows.isEmpty()) {
            "No activity recorded for $monthLabel."
        } else {
            rows.mapIndexed { idx, row ->
                val name = guild.getMemberById(row.discordId)?.effectiveName ?: "Unknown (${row.discordId})"
                val voice = formatDuration(row.voiceSeconds)
                "**#${idx + 1}** $name — ${row.creditsEarned} credits · ${row.endTotal} total · $voice"
            }.joinToString("\n")
        }

        return EmbedBuilder()
            .setTitle("Monthly Social Credit Leaderboard — $monthLabel")
            .setDescription(description)
            .setColor(Color(0xFFD700))
            .setFooter(
                "Credits = earned last month (voice-time included, UBI excluded). " +
                        "Total = balance at month end. Frozen at the 1st."
            )
            .build()
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private data class MonthlyRow(
        val discordId: Long,
        val creditsEarned: Long,
        val endTotal: Long,
        val voiceSeconds: Long
    )
}
