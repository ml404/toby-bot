package web.service

import database.dto.activity.InstallEventType
import database.dto.guild.ConfigDto
import database.persistence.activity.MessageDailyCountPersistence
import database.service.activity.InstallEventService
import database.service.guild.ConfigService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Assembles the bot-operator "who installed me" view for `/admin/installs`:
 * the per-guild rows, the headline summary stats, the churn numbers, and
 * the deeper insights (health, liveness, feature adoption, audience).
 *
 * Three data sources, deliberately kept distinct:
 *  - **Current state** — JDA's guild cache joined to the install *wizard*
 *    sentinel ([ConfigDto.Configurations.INSTALL_MODE] / `INSTALLED_AT`).
 *  - **Lifecycle ledger** — [InstallEventService] records every JOIN/LEAVE
 *    from deploy onward, so churn can be reported even though the snapshot
 *    can't see departed guilds.
 *  - **Activity liveness** — [MessageDailyCountPersistence] gives the last
 *    message-activity day per guild so installs can be split active vs quiet.
 *
 * Per-guild detail (permissions, boost tier, locale, channel/role counts,
 * age, feature flags) is read straight off the cached [Guild] — all
 * in-memory, no Discord API round-trips — and wrapped defensively so one
 * guild with an odd cache state can't blank the whole page.
 */
@Service
class AdminInstallsService(
    private val jda: JDA,
    private val configService: ConfigService,
    private val installEventService: InstallEventService,
    private val messageDailyCounts: MessageDailyCountPersistence,
    private val clock: Clock = Clock.systemUTC(),
) {

    data class InstallRow(
        val guildId: String,
        val guildName: String,
        val iconUrl: String?,
        val ownerId: String,
        val ownerName: String?,
        val memberCount: Int,
        val installMode: String,
        val installedAtMillis: Long?,
        val botJoinedAtMillis: Long?,
        val serverCreatedMillis: Long?,
        val boostTier: Int,
        val boostCount: Int,
        val locale: String?,
        val channelCount: Int,
        val roleCount: Int,
        val features: List<String>,
        val daysSinceInstall: Long?,
        val serverAgeDays: Long?,
        /** Permission/reachability problems, e.g. "Can't post". Empty = healthy. */
        val healthIssues: List<String>,
        val lastActiveMillis: Long?,
        /** No message activity within [DORMANT_DAYS] (or none ever recorded). */
        val isDormant: Boolean,
    ) {
        val wizardCompleted: Boolean get() = installMode != LEGACY
        val isHealthy: Boolean get() = healthIssues.isEmpty()
        val installedAtDisplay: String get() = formatMillis(installedAtMillis)
        val botJoinedAtDisplay: String get() = formatMillis(botJoinedAtMillis)
        val serverCreatedDisplay: String get() = formatMillis(serverCreatedMillis)
        val lastActiveDisplay: String get() = formatMillis(lastActiveMillis)
    }

    data class InstallStats(
        val totalInstalls: Int,
        val expressCount: Int,
        val customCount: Int,
        val legacyCount: Int,
        val totalMembers: Long,
        val avgMembers: Long,
        val installsLast7Days: Int,
        val installsLast30Days: Int,
        val lifetimeJoins: Long,
        val lifetimeLeaves: Long,
        val netGrowth: Long,
        val joinsLast30Days: Long,
        val leavesLast30Days: Long,
        val hasLedgerData: Boolean,
        // --- health + liveness ---
        val brokenInstalls: Int,
        val activeInstalls: Int,
        val dormantInstalls: Int,
    )

    data class LabeledCount(val label: String, val count: Int) {
        /** Bar width as a percentage of [outOf]; clamped to [0, 100]. */
        fun pct(outOf: Int): Int = if (outOf <= 0) 0 else ((count.toDouble() / outOf) * 100).toInt().coerceIn(0, 100)
    }

    /** Aggregate breakdowns rendered as the page's "insights" panels. */
    data class InstallInsights(
        val total: Int,
        val featureAdoption: List<LabeledCount>,
        val sizeBuckets: List<LabeledCount>,
        val localeDistribution: List<LabeledCount>,
        val boostDistribution: List<LabeledCount>,
    )

    fun listInstalls(): List<InstallRow> {
        val nowMillis = clock.millis()
        val dormantCutoff = clock.instant().minus(DORMANT_DAYS, ChronoUnit.DAYS).toEpochMilli()
        val configByGuild = configByGuild()
        val lastActiveByGuild = runCatching { messageDailyCounts.findLastActiveByGuild() }.getOrDefault(emptyMap())

        return jda.guildCache.mapNotNull { guild ->
            val cfg = configByGuild[guild.id].orEmpty()
            val mode = cfg[ConfigDto.Configurations.INSTALL_MODE.configValue]
                ?.takeIf { it.isNotBlank() } ?: LEGACY
            val installedAt = cfg[ConfigDto.Configurations.INSTALLED_AT.configValue]?.toLongOrNull()
            val ownerName = guild.getMemberById(guild.ownerIdLong)?.effectiveName
            val detail = guildDetail(guild)
            val lastActive = lastActiveByGuild[guild.idLong]
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

            InstallRow(
                guildId = guild.id,
                guildName = guild.name,
                iconUrl = guild.iconUrl,
                ownerId = guild.ownerId,
                ownerName = ownerName,
                memberCount = guild.memberCount,
                installMode = mode,
                installedAtMillis = installedAt,
                botJoinedAtMillis = detail.botJoinedAtMillis,
                serverCreatedMillis = detail.serverCreatedMillis,
                boostTier = detail.boostTier,
                boostCount = detail.boostCount,
                locale = detail.locale,
                channelCount = detail.channelCount,
                roleCount = detail.roleCount,
                features = detail.features,
                daysSinceInstall = installedAt?.let { daysBetween(it, nowMillis) },
                serverAgeDays = detail.serverCreatedMillis?.let { daysBetween(it, nowMillis) },
                healthIssues = healthIssues(guild),
                lastActiveMillis = lastActive,
                isDormant = lastActive == null || lastActive < dormantCutoff,
            )
        }.sortedWith(
            compareByDescending<InstallRow> { it.installedAtMillis ?: Long.MIN_VALUE }
                .thenBy { it.guildName.lowercase() }
        )
    }

    fun buildStats(rows: List<InstallRow>): InstallStats {
        val now = clock.instant()
        val totalMembers = rows.sumOf { it.memberCount.toLong() }
        val last7 = now.minus(7, ChronoUnit.DAYS).toEpochMilli()
        val last30 = now.minus(30, ChronoUnit.DAYS).toEpochMilli()

        val lifetimeJoins = installEventService.countByType(InstallEventType.JOIN)
        val lifetimeLeaves = installEventService.countByType(InstallEventType.LEAVE)
        val joins30 = installEventService.countByTypeSince(InstallEventType.JOIN, now.minus(30, ChronoUnit.DAYS))
        val leaves30 = installEventService.countByTypeSince(InstallEventType.LEAVE, now.minus(30, ChronoUnit.DAYS))

        return InstallStats(
            totalInstalls = rows.size,
            expressCount = rows.count { it.installMode == "express" },
            customCount = rows.count { it.installMode == "custom" },
            legacyCount = rows.count { it.installMode == LEGACY },
            totalMembers = totalMembers,
            avgMembers = if (rows.isEmpty()) 0L else totalMembers / rows.size,
            installsLast7Days = rows.count { (it.installedAtMillis ?: 0L) >= last7 },
            installsLast30Days = rows.count { (it.installedAtMillis ?: 0L) >= last30 },
            lifetimeJoins = lifetimeJoins,
            lifetimeLeaves = lifetimeLeaves,
            netGrowth = lifetimeJoins - lifetimeLeaves,
            joinsLast30Days = joins30,
            leavesLast30Days = leaves30,
            hasLedgerData = (lifetimeJoins + lifetimeLeaves) > 0L,
            brokenInstalls = rows.count { !it.isHealthy },
            activeInstalls = rows.count { !it.isDormant },
            dormantInstalls = rows.count { it.isDormant },
        )
    }

    fun buildInsights(rows: List<InstallRow>): InstallInsights {
        val configByGuild = configByGuild()
        // Restrict feature-adoption to guilds we actually have rows for so
        // stale config from a since-departed guild doesn't inflate counts.
        val liveGuildIds = rows.map { it.guildId }.toSet()
        val liveConfig = configByGuild.filterKeys { it in liveGuildIds }

        val featureAdoption = FEATURE_PREDICATES.map { (label, predicate) ->
            LabeledCount(label, liveConfig.values.count(predicate))
        }.sortedByDescending { it.count }

        val sizeBuckets = listOf(
            LabeledCount("Small (<50)", rows.count { it.memberCount < 50 }),
            LabeledCount("Medium (50–499)", rows.count { it.memberCount in 50..499 }),
            LabeledCount("Large (500–4999)", rows.count { it.memberCount in 500..4999 }),
            LabeledCount("Huge (5000+)", rows.count { it.memberCount >= 5000 }),
        )

        val localeDistribution = rows
            .mapNotNull { it.locale }
            .groupingBy { it }.eachCount()
            .map { (label, count) -> LabeledCount(label, count) }
            .sortedByDescending { it.count }
            .take(6)

        val boostDistribution = (0..3).map { tier ->
            LabeledCount(if (tier == 0) "No boost" else "Tier $tier", rows.count { it.boostTier == tier })
        }.filter { it.count > 0 }

        return InstallInsights(
            total = rows.size,
            featureAdoption = featureAdoption,
            sizeBuckets = sizeBuckets,
            localeDistribution = localeDistribution,
            boostDistribution = boostDistribution,
        )
    }

    private fun configByGuild(): Map<String, Map<String, String>> =
        configService.listAllConfig().orEmpty()
            .filterNotNull()
            .filter { it.guildId != null && it.guildId != GLOBAL_GUILD && it.name != null }
            .groupBy { it.guildId!! }
            .mapValues { (_, rows) -> rows.associate { it.name!! to (it.value ?: "") } }

    private data class GuildDetail(
        val botJoinedAtMillis: Long? = null,
        val serverCreatedMillis: Long? = null,
        val boostTier: Int = 0,
        val boostCount: Int = 0,
        val locale: String? = null,
        val channelCount: Int = 0,
        val roleCount: Int = 0,
        val features: List<String> = emptyList(),
    )

    private fun guildDetail(guild: Guild): GuildDetail = runCatching {
        GuildDetail(
            botJoinedAtMillis = guild.selfMember.timeJoined.toInstant().toEpochMilli(),
            serverCreatedMillis = guild.timeCreated.toInstant().toEpochMilli(),
            boostTier = guild.boostTier.key,
            boostCount = guild.boostCount,
            locale = guild.locale.languageName,
            channelCount = guild.textChannels.size + guild.voiceChannels.size,
            roleCount = guild.roles.size,
            features = NOTABLE_FEATURES.filter { it in guild.features }.map { prettyFeature(it) },
        )
    }.getOrDefault(GuildDetail())

    /** Permission/reachability problems for the bot in [guild]; never throws. */
    private fun healthIssues(guild: Guild): List<String> = runCatching {
        val self = guild.selfMember
        // Administrator subsumes everything we'd flag — short-circuit.
        if (self.hasPermission(Permission.ADMINISTRATOR)) return@runCatching emptyList()
        val issues = mutableListOf<String>()
        val canPost = guild.systemChannel
            ?.let { self.hasPermission(it, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND) } == true ||
            guild.textChannels.any { self.hasPermission(it, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND) }
        if (!canPost) issues.add("Can't post")
        if (!self.hasPermission(Permission.MANAGE_ROLES)) issues.add("No Manage Roles")
        issues
    }.getOrDefault(emptyList())

    companion object {
        const val LEGACY = "legacy/unknown"
        const val DORMANT_DAYS = 30L
        private const val GLOBAL_GUILD = "all"
        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")

        private val NOTABLE_FEATURES: List<String> = listOf(
            "COMMUNITY", "PARTNERED", "VERIFIED", "DISCOVERABLE", "BANNER", "VANITY_URL",
        )

        /** Optional features we can detect from the per-guild config map. */
        private val FEATURE_PREDICATES: List<Pair<String, (Map<String, String>) -> Boolean>> = listOf(
            "Activity tracking" to { c -> c[ConfigDto.Configurations.ACTIVITY_TRACKING.configValue] == "true" },
            "Daily lottery" to { c -> c[ConfigDto.Configurations.LOTTERY_DAILY_ENABLED.configValue] == "true" },
            "Welcome messages" to { c -> c[ConfigDto.Configurations.WELCOME_ENABLED.configValue] == "true" },
            "Goodbye messages" to { c -> c[ConfigDto.Configurations.GOODBYE_ENABLED.configValue] == "true" },
            "Leaderboard channel" to { c -> c[ConfigDto.Configurations.LEADERBOARD_CHANNEL.configValue].orEmpty().isNotBlank() },
            "Level-up channel" to { c -> c[ConfigDto.Configurations.LEVEL_UP_CHANNEL.configValue].orEmpty().isNotBlank() },
            "UBI payouts" to { c -> (c[ConfigDto.Configurations.UBI_DAILY_AMOUNT.configValue]?.toIntOrNull() ?: 0) > 0 },
            "Lottery channel" to { c -> c[ConfigDto.Configurations.LOTTERY_CHANNEL.configValue].orEmpty().isNotBlank() },
        )

        private fun prettyFeature(raw: String): String =
            raw.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::titlecase) }

        private fun formatMillis(millis: Long?): String = millis?.let {
            DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC))
        } ?: ""

        private fun daysBetween(fromMillis: Long, toMillis: Long): Long =
            ChronoUnit.DAYS.between(Instant.ofEpochMilli(fromMillis), Instant.ofEpochMilli(toMillis))
    }
}
