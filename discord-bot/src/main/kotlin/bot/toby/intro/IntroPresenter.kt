package bot.toby.intro

import bot.toby.BOT_WEB_URL
import common.discord.embed
import common.discord.field
import common.intro.IntroClip
import common.intro.IntroHealth
import database.dto.music.MusicDto
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color
import java.time.Duration
import java.time.Instant

/**
 * Rendering for everything intro-shaped that Discord shows: the select-menu
 * options behind `/editintro` and `/deleteintro`, the `/listintros` embed,
 * and the deep link across to the web dashboard.
 *
 * Centralising it fixes two problems the per-command copies had:
 *  - options were built in raw insertion order, so the numbering a user saw
 *    in the menu could disagree with the slot numbering everywhere else;
 *  - labels were passed to JDA unbounded, and a YouTube title over Discord's
 *    100-character select-option limit made `/editintro` and `/deleteintro`
 *    throw instead of opening.
 */
object IntroPresenter {

    /** Discord's hard cap on select-option labels and descriptions. */
    private const val SELECT_TEXT_LIMIT = 100

    private val INTRO_COLOR: Color = Color(88, 101, 242) // Discord blurple

    fun sorted(intros: Collection<MusicDto>): List<MusicDto> = intros.sortedBy { it.index ?: Int.MAX_VALUE }

    /** True when the intro's blob holds a URL rather than uploaded audio. */
    fun isUrlIntro(intro: MusicDto): Boolean = intro.musicBlob
        ?.let { String(it) }
        ?.let { it.startsWith("http://") || it.startsWith("https://") }
        ?: false

    fun displayName(intro: MusicDto): String = intro.fileName?.takeIf { it.isNotBlank() } ?: "Unknown"

    /**
     * `Slot #2 · Volume 90% · 0:03 – 0:12` — the at-a-glance summary shown
     * under each select-menu option and inside the `/listintros` embed.
     * A disabled intro says so first, since that's the thing you'd want to
     * notice when wondering why it never plays.
     */
    fun summary(intro: MusicDto): String = buildString {
        // Broken first, then off: both explain "why doesn't this play", and a
        // broken intro is the one the user didn't choose and can't see.
        if (isBroken(intro)) append("BROKEN · ")
        if (!intro.enabled) append("OFF · ")
        append("Slot #${intro.index ?: '?'}")
        append(" · Volume ${intro.introVolume ?: 100}%")
        append(" · ${IntroClip.describe(intro.startMs, intro.endMs)}")
    }

    /** What the source said last time it refused, for the surfaces that show it. */
    private fun failureReason(intro: MusicDto): String =
        intro.lastFailureReason ?: "source could not be loaded"

    /** Whether this intro's source has failed enough times to be called dead. */
    fun isBroken(intro: MusicDto): Boolean = IntroHealth.isUnhealthy(intro.failureCount)

    /**
     * `Played 12× · last 3 days ago` — or null for an intro that has never
     * played, where "Played 0×" is noise rather than information.
     *
     * Play counts only start from the deploy that introduced them, so an
     * old intro reading as never-played is expected for a while.
     */
    fun playSummary(intro: MusicDto, now: Instant = Instant.now()): String? {
        if (intro.playCount <= 0) return null
        val last = intro.lastPlayedAt?.let { " · last ${relativeTime(it, now)}" }.orEmpty()
        return "Played ${intro.playCount}×$last"
    }

    /** Coarse "3 days ago" phrasing — exact timestamps aren't the point here. */
    internal fun relativeTime(then: Instant, now: Instant): String {
        val seconds = Duration.between(then, now).seconds
        return when {
            seconds < 0 -> "just now"
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            seconds < 86_400 * 30 -> "${seconds / 86_400}d ago"
            else -> "${seconds / (86_400 * 30)}mo ago"
        }
    }

    /**
     * Select options in slot order, with labels and descriptions clamped to
     * Discord's limits so a long track title can't break the menu.
     */
    fun selectOptions(intros: Collection<MusicDto>): List<SelectOption> = sorted(intros).map { intro ->
        SelectOption.of(truncate(displayName(intro)), intro.id.orEmpty())
            .withDescription(truncate(summary(intro)))
    }

    fun listEmbed(member: Member, intros: Collection<MusicDto>, maxIntros: Int): MessageEmbed {
        val ordered = sorted(intros)
        return embed(
            title = "Intro songs — ${member.effectiveName}",
            color = INTRO_COLOR,
            description = when {
                ordered.isEmpty() ->
                    "No intros set yet. Use `/setintro link` or `/setintro attachment` to add one."
                ordered.none { it.enabled } ->
                    "Every intro is switched off, so nothing plays on join. " +
                        "Turn one back on with `/editintro`."
                ordered.filter { it.enabled }.all { isBroken(it) } ->
                    "Every intro that's switched on has stopped loading, so nothing plays on join. " +
                        "Replacing the link with `/setintro` fixes it."
                else ->
                    "TobyBot plays one of these at random when you join a voice channel."
            },
        ) {
            // EmbedBuilder rejects anything that isn't a real http(s) URL, and
            // a member without a resolvable avatar is not worth an exception.
            member.effectiveAvatarUrl
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.let { setThumbnail(it) }
            ordered.forEach { intro ->
                val marker = when {
                    isBroken(intro) -> " ⚠️"
                    !intro.enabled -> " (off)"
                    else -> ""
                }
                field(
                    name = "#${intro.index ?: '?'} · ${truncate(displayName(intro), FIELD_NAME_LIMIT)}$marker",
                    value = buildString {
                        append("Volume **${intro.introVolume ?: 100}%**")
                        append(" · Clip **${IntroClip.describe(intro.startMs, intro.endMs)}**")
                        append(" · ${if (isUrlIntro(intro)) "Link" else "Uploaded file"}")
                        if (!intro.enabled) append(" · **skipped on join**")
                        playSummary(intro)?.let { append("\n$it") }
                        // The reason is the actionable half — "video
                        // unavailable" and "region blocked" call for
                        // different fixes — and it is written on the very
                        // first failure. Withholding it until the second left
                        // somebody who had just heard silence looking at a row
                        // that described itself as perfectly fine.
                        when {
                            isBroken(intro) -> {
                                append("\n**Not playing** — ")
                                append(truncate(failureReason(intro), REASON_LIMIT))
                            }
                            intro.failureCount > 0 -> {
                                // Not called broken yet: one failure is as
                                // likely to have been a blip as a dead link.
                                append("\nDidn't play last time — ")
                                append(truncate(failureReason(intro), REASON_LIMIT))
                            }
                        }
                    },
                )
            }
            setFooter("${ordered.size}/$maxIntros slots used · edit with /editintro, remove with /deleteintro")
        }
    }

    /** Deep link to the guild's intro page on the web dashboard. */
    fun webDashboardButton(guildId: Long, label: String = "Manage on the web"): Button =
        Button.link("$BOT_WEB_URL/intro/$guildId", label)

    private const val FIELD_NAME_LIMIT = 200

    /** Failure reasons come from the audio source and can be paragraphs. */
    private const val REASON_LIMIT = 200

    private fun truncate(text: String, limit: Int = SELECT_TEXT_LIMIT): String =
        if (text.length <= limit) text else text.take(limit - 1).trimEnd() + "…"
}
