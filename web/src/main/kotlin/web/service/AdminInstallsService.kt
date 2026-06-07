package web.service

import database.dto.activity.InstallEventType
import database.dto.guild.ConfigDto
import database.service.activity.InstallEventService
import database.service.guild.ConfigService
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Assembles the bot-operator "who installed me" view for `/admin/installs`:
 * the per-guild rows, the headline summary stats, and the churn numbers.
 *
 * Two data sources, deliberately kept distinct:
 *  - **Current state** — JDA's guild cache (every guild the bot is in right
 *    now) joined to the install *wizard* sentinel
 *    ([ConfigDto.Configurations.INSTALL_MODE] / `INSTALLED_AT`). This is a
 *    snapshot; it has no notion of guilds that have since left.
 *  - **Lifecycle ledger** — [InstallEventService] records every JOIN/LEAVE
 *    from deploy onward, so churn (removals, net growth) can be reported
 *    even though the config snapshot can't see departed guilds.
 *
 * Per-guild detail (boost tier, locale, channel/role counts, age, feature
 * flags) is read straight off the cached [Guild] — all in-memory, no
 * Discord API round-trips — and wrapped defensively so one guild with an
 * odd cache state can't blank the whole page.
 */
@Service
class AdminInstallsService(
    private val jda: JDA,
    private val configService: ConfigService,
    private val installEventService: InstallEventService,
    private val clock: Clock = Clock.systemUTC(),
) {

    data class InstallRow(
        val guildId: String,
        val guildName: String,
        val iconUrl: String?,
        val ownerId: String,
        /** Owner display name when the member is cached; null → template shows the id. */
        val ownerName: String?,
        val memberCount: Int,
        /** "express" | "custom" | [LEGACY]. */
        val installMode: String,
        val installedAtMillis: Long?,
        // --- deducible server info (all best-effort, off the cached Guild) ---
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
    ) {
        /** True when the owner completed the wizard (vs. legacy/skipped). */
        val wizardCompleted: Boolean get() = installMode != LEGACY
        val installedAtDisplay: String get() = formatMillis(installedAtMillis)
        val botJoinedAtDisplay: String get() = formatMillis(botJoinedAtMillis)
        val serverCreatedDisplay: String get() = formatMillis(serverCreatedMillis)
    }

    /** Headline numbers for the stats strip. */
    data class InstallStats(
        val totalInstalls: Int,
        val expressCount: Int,
        val customCount: Int,
        val legacyCount: Int,
        val totalMembers: Long,
        val avgMembers: Long,
        val installsLast7Days: Int,
        val installsLast30Days: Int,
        // churn — from the lifecycle ledger (post-deploy only)
        val lifetimeJoins: Long,
        val lifetimeLeaves: Long,
        val netGrowth: Long,
        val joinsLast30Days: Long,
        val leavesLast30Days: Long,
        /** False until the ledger has accrued at least one event. */
        val hasLedgerData: Boolean,
    )

    fun listInstalls(): List<InstallRow> {
        val nowMillis = clock.millis()
        // One bulk read of every config row (cached, @Cacheable["configs"]),
        // not N per-guild getConfigByName calls. Index by guild → key → value,
        // skipping the global "all" pseudo-guild.
        val configByGuild: Map<String, Map<String, String>> =
            configService.listAllConfig().orEmpty()
                .filterNotNull()
                .filter { it.guildId != null && it.guildId != GLOBAL_GUILD && it.name != null }
                .groupBy { it.guildId!! }
                .mapValues { (_, rows) -> rows.associate { it.name!! to (it.value ?: "") } }

        return jda.guildCache.mapNotNull { guild ->
            val cfg = configByGuild[guild.id].orEmpty()
            val mode = cfg[ConfigDto.Configurations.INSTALL_MODE.configValue]
                ?.takeIf { it.isNotBlank() } ?: LEGACY
            val installedAt = cfg[ConfigDto.Configurations.INSTALLED_AT.configValue]?.toLongOrNull()
            // Cache-only owner lookup. retrieveOwner() would be a network
            // round-trip per guild — unacceptable across the whole list, so
            // an uncached owner just shows as its id.
            val ownerName = guild.getMemberById(guild.ownerIdLong)?.effectiveName
            val detail = guildDetail(guild)

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
            )
        }.sortedWith(
            // Newest installs first; legacy/unknown (null date) sink to the bottom.
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
        )
    }

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

    /** Best-effort detail straight off the cached guild; never throws. */
    private fun guildDetail(guild: Guild): GuildDetail = runCatching {
        GuildDetail(
            botJoinedAtMillis = guild.selfMember.timeJoined.toInstant().toEpochMilli(),
            serverCreatedMillis = guild.timeCreated.toInstant().toEpochMilli(),
            boostTier = guild.boostTier.key,
            boostCount = guild.boostCount,
            locale = guild.locale.languageName,
            channelCount = guild.textChannels.size + guild.voiceChannels.size,
            roleCount = guild.roles.size,
            features = NOTABLE_FEATURES
                .filter { it in guild.features }
                .map { prettyFeature(it) },
        )
    }.getOrDefault(GuildDetail())

    companion object {
        const val LEGACY = "legacy/unknown"
        private const val GLOBAL_GUILD = "all"
        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")

        /** Discord guild feature flags worth surfacing as badges (ignore the noisy rest). */
        private val NOTABLE_FEATURES: List<String> = listOf(
            "COMMUNITY", "PARTNERED", "VERIFIED", "DISCOVERABLE", "BANNER", "VANITY_URL",
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
