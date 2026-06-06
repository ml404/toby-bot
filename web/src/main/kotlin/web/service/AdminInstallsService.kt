package web.service

import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Assembles the bot-operator "who installed me" list for `/admin/installs`.
 *
 * Every guild JDA is currently in counts as an install; the install
 * *wizard* sentinel ([ConfigDto.Configurations.INSTALL_MODE] /
 * `INSTALLED_AT`, written by `InstallSentinel`) tells us how/when the
 * owner completed setup. Guilds the bot joined before the wizard existed
 * (or whose owner skipped it) have no sentinel and surface as
 * "legacy/unknown" with no date — they're still installs, just unmeasured.
 *
 * Kept separate from the 1750-line [ModerationWebService] (parallels the
 * [CasinoAuditService] extraction) and deliberately does NOT route through
 * [ModerationWebService.getGuildOverview], which iterates every member of
 * every guild — far too heavy for a flat list across the whole install base.
 */
@Service
class AdminInstallsService(
    private val jda: JDA,
    private val configService: ConfigService,
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
    ) {
        /** Human date for the template; blank when the wizard was never completed. */
        val installedAtDisplay: String
            get() = installedAtMillis?.let {
                DATE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC))
            } ?: ""
    }

    fun listInstalls(): List<InstallRow> {
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

            InstallRow(
                guildId = guild.id,
                guildName = guild.name,
                iconUrl = guild.iconUrl,
                ownerId = guild.ownerId,
                ownerName = ownerName,
                memberCount = guild.memberCount,
                installMode = mode,
                installedAtMillis = installedAt,
            )
        }.sortedWith(
            // Newest installs first; legacy/unknown (null date) sink to the bottom.
            compareByDescending<InstallRow> { it.installedAtMillis ?: Long.MIN_VALUE }
                .thenBy { it.guildName.lowercase() }
        )
    }

    companion object {
        const val LEGACY = "legacy/unknown"
        private const val GLOBAL_GUILD = "all"
        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
    }
}
