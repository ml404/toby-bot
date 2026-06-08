package bot.toby.install

import common.casino.coinflip.Coinflip
import database.dto.guild.ConfigDto.Configurations
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Builds the owner-facing "how is this server set up?" snapshot rendered by
 * `/install summary` and the control panel's "View setup" button. Pure: it
 * takes a [ConfigReader] plus the already-computed economy values (so the
 * caller owns the service wiring and this stays trivially testable), reads
 * the high-signal config, resolves channel ids against [Guild], and — the
 * valuable part — flags recommended-but-unset settings as next steps rather
 * than dumping the whole 100-key schema.
 */
object InstallSummary {

    private val DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC)

    /** Channel configs surfaced in the summary, in display order. */
    private val CHANNEL_LINES: List<Pair<String, Configurations>> = listOf(
        "Leaderboard" to Configurations.LEADERBOARD_CHANNEL,
        "Level-up announce" to Configurations.LEVEL_UP_CHANNEL,
        "Achievement announce" to Configurations.ACHIEVEMENT_ANNOUNCE_CHANNEL,
        "Casino modlog" to Configurations.CASINO_MODLOG_CHANNEL_ID,
        "Move target" to Configurations.MOVE,
    )

    fun embed(
        guild: Guild,
        reader: ConfigReader,
        jackpotPool: Long,
        winChanceDisplay: String,
        webBaseUrl: String,
    ): MessageEmbed {
        val mode = reader(Configurations.INSTALL_MODE)
        val builder = EmbedBuilder()
            .setTitle("⚙️ Toby Bot — setup summary for ${guild.name}")
            .setDescription(headerLine(mode, reader(Configurations.INSTALLED_AT)?.toLongOrNull()))

        val coinMin = reader(Configurations.COINFLIP_MIN_STAKE)?.toLongOrNull() ?: Coinflip.MIN_STAKE
        val coinMax = reader(Configurations.COINFLIP_MAX_STAKE)?.toLongOrNull() ?: Coinflip.MAX_STAKE
        builder.addField(
            "🎲 Casino & economy",
            "Jackpot pool: **$jackpotPool** 💰\n" +
                "Jackpot win chance: **$winChanceDisplay%**\n" +
                "Coinflip stakes: $coinMin–$coinMax credits",
            false,
        )

        val features = OptInFeatures.entries.joinToString("\n") { f ->
            "${if (reader(f.key) == "true") "✅" else "⬜"} ${f.label}"
        }
        builder.addField(
            "✨ Features",
            "✅ Casino & games · ✅ Music · ✅ Daily streak\n$features",
            false,
        )

        val channels = CHANNEL_LINES.joinToString("\n") { (label, key) ->
            val mention = reader(key)?.toLongOrNull()?.let { guild.getGuildChannelById(it)?.asMention }
            "$label: ${mention ?: "*not set*"}"
        }
        builder.addField("📺 Channels", channels, false)

        builder.addField(
            "🌐 Web dashboard",
            if (webBaseUrl.isNotBlank()) "[Open your dashboard]($webBaseUrl/profile/${guild.id})"
            else "Not configured for this deployment.",
            false,
        )

        buildSuggestions(reader).takeIf { it.isNotEmpty() }?.let { tips ->
            builder.addField("💡 Suggested next steps", tips.joinToString("\n") { "• $it" }, false)
        }

        builder.setFooter("Owner-only · reflects your current config")
        return builder.build()
    }

    private fun headerLine(mode: String?, installedAt: Long?): String {
        if (mode.isNullOrBlank()) {
            return "⚠️ Setup isn't finished yet — run `/install setup` to get going."
        }
        val date = installedAt?.let { DATE_FMT.format(Instant.ofEpochMilli(it)) } ?: "an earlier date"
        val modeLabel = mode.replaceFirstChar { it.uppercase() }
        return "Installed **$modeLabel** · $date · re-run `/install setup` to change anything."
    }

    private fun buildSuggestions(reader: ConfigReader): List<String> {
        val out = mutableListOf<String>()
        val activityOn = reader(Configurations.ACTIVITY_TRACKING) == "true"
        val lotteryOn = reader(Configurations.LOTTERY_DAILY_ENABLED) == "true"

        if (!activityOn) {
            out += "Turn on **Activity tracking** for XP & leaderboards — `/install setup` → Optional features."
        }
        if (!lotteryOn) {
            out += "Enable the **Daily lottery** for a server-wide draw — `/install setup` → Optional features."
        }
        if (reader(Configurations.LEADERBOARD_CHANNEL)?.toLongOrNull() == null) {
            out += "Set a **leaderboard channel** so winners get a public shoutout — `/setconfig general`."
        }
        if (activityOn && reader(Configurations.LEVEL_UP_CHANNEL)?.toLongOrNull() == null) {
            out += "Set a **level-up announce channel** — `/setconfig activity`."
        }
        if (reader(Configurations.ACHIEVEMENT_ANNOUNCE_CHANNEL)?.toLongOrNull() == null) {
            out += "Set an **achievement announce channel** for public unlock shoutouts."
        }
        return out
    }
}
