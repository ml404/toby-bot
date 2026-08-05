package bot.toby.command.commands.music.intro

import bot.toby.command.commands.music.MusicCommand
import bot.toby.intro.IntroPresenter
import bot.toby.lavaplayer.PlayerManager
import common.discord.embed
import common.discord.field
import common.intro.IntroGuildPolicy
import common.intro.IntroHealth
import core.command.CommandContext
import database.dto.guild.ConfigDto.Configurations.INTROS_ENABLED
import database.dto.guild.ConfigDto.Configurations.INTRO_EXCLUDED_CHANNELS
import database.dto.user.UserDto
import database.persistence.music.GuildIntroStats
import database.service.guild.ConfigService
import database.service.music.MusicFileService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Guild
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

/**
 * `/introstats` — the server-wide view of the intro feature.
 *
 * Three things had no home before this. How many intros are broken, which
 * nobody could see at all. How much audio the server is storing, which grows
 * quietly at up to 550KB × 3 per member and is only interesting shortly before
 * it becomes a problem. And whether the feature is used at all, which nothing
 * recorded.
 *
 * Super-user only: it reports across every member, so it's a moderation view
 * rather than a personal one. Members get their own numbers from `/listintros`.
 */
@Component
class IntroStatsCommand @Autowired constructor(
    private val musicFileService: MusicFileService,
    private val configService: ConfigService,
) : MusicCommand {

    override val ephemeral: Boolean = true

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int
    ) {
        val event = ctx.event
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)

        if (!requestingUserDto.superUser) {
            sendErrorMessage(event, deleteDelay)
            return
        }
        val guild = ctx.guild ?: run {
            event.hook.sendMessage("Intros are per-server — run this inside the server you want to inspect.")
                .setEphemeral(true).queue()
            return
        }

        val stats = musicFileService.getGuildIntroStats(guild.idLong, IntroHealth.UNHEALTHY_AFTER_FAILURES)
        event.hook.sendMessageEmbeds(statsEmbed(guild, stats, instance.isSourceRefusingRequests()))
            .addComponents(ActionRow.of(IntroPresenter.webDashboardButton(guild.idLong)))
            .setEphemeral(true)
            .queue()
    }

    private fun statsEmbed(guild: Guild, stats: GuildIntroStats, sourceRefusing: Boolean) = embed(
        title = "Intro report — ${guild.name}",
        color = if (sourceRefusing) OUTAGE_COLOR else INTRO_COLOR,
        description = if (stats.introCount == 0L) {
            "Nobody on this server has set an intro yet."
        } else {
            "${stats.introCount} intro${plural(stats.introCount)} across " +
                "${stats.userCount} member${plural(stats.userCount)}."
        },
    ) {
        // First, because it changes how everything under it should be read.
        if (sourceRefusing) {
            field(
                name = "⚠️ The audio source is refusing us",
                value = "Nothing is playing anywhere at the moment — this is TobyBot being rate-limited or " +
                    "blocked, not a problem with anyone's intro. It usually clears on its own.\n\n" +
                    "Intro health is **paused** while it lasts, so nobody is being told their intro is " +
                    "broken and the counts below all predate it.",
            )
        }
        field(name = "Plays", value = "**${stats.totalPlays}** since play counts were added", inline = true)
        field(name = "Stored audio", value = "**${formatBytes(stats.storedBytes)}**", inline = true)
        field(
            name = "Switched off",
            value = "**${stats.disabledCount}** by their owner",
            inline = true,
        )
        field(
            name = "Not playing",
            value = when {
                stats.brokenCount == 0L -> "**0** — every intro is loading fine"
                // The usual advice is actively wrong mid-outage: their links
                // are fine, and no DM went out to explain the silence.
                sourceRefusing -> "**${stats.brokenCount}** were failing before the outage started. " +
                    "Worth re-checking once the source is back."
                // Owners are DM'd when theirs breaks, but that DM can be
                // missed or muted, so the count is repeated where an admin
                // will see it.
                else -> "**${stats.brokenCount}** failing to load. Owners have been " +
                    "DM'd; they can replace the link with `/setintro`."
            },
        )
        field(name = "Server settings", value = describeSettings(guild))
        setFooter("Storage is the audio uploaded to TobyBot; link intros store only the link.")
    }

    private fun describeSettings(guild: Guild): String {
        val enabledRaw = configService.getConfigByName(INTROS_ENABLED.configValue, guild.id)?.value
        val excludedRaw = configService.getConfigByName(INTRO_EXCLUDED_CHANNELS.configValue, guild.id)?.value
        if (!IntroGuildPolicy.introsEnabled(enabledRaw)) {
            return "Intros are **off** server-wide (`/setconfig intro`)."
        }
        val excluded = IntroGuildPolicy.excludedChannelIds(excludedRaw)
        if (excluded.isEmpty()) return "Intros are **on** in every voice channel."
        val named = excluded.joinToString(", ") { id ->
            guild.getVoiceChannelById(id)?.let { "#${it.name}" } ?: "`$id`"
        }
        return "Intros are **on**, except in: $named"
    }

    private fun plural(count: Long) = if (count == 1L) "" else "s"

    /**
     * Binary units, because the thing being sized is a Postgres column and the
     * limit an admin will eventually hit is quoted the same way.
     */
    internal fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    override val name: String get() = "introstats"

    override val description: String get() = "Server-wide intro report: usage, broken intros and storage (super-users)."

    companion object {
        private val INTRO_COLOR: Color = Color(88, 101, 242) // Discord blurple

        /** Amber, so an outage is visible before a word of it is read. */
        private val OUTAGE_COLOR: Color = Color(250, 166, 26)
    }
}
