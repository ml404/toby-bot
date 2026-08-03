package bot.toby.intro

import bot.toby.BOT_WEB_URL
import common.discord.embed
import common.discord.field
import common.intro.IntroClip
import database.dto.music.MusicDto
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.selections.SelectOption
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

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
        if (!intro.enabled) append("OFF · ")
        append("Slot #${intro.index ?: '?'}")
        append(" · Volume ${intro.introVolume ?: 100}%")
        append(" · ${IntroClip.describe(intro.startMs, intro.endMs)}")
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
                val marker = if (intro.enabled) "" else " (off)"
                field(
                    name = "#${intro.index ?: '?'} · ${truncate(displayName(intro), FIELD_NAME_LIMIT)}$marker",
                    value = buildString {
                        append("Volume **${intro.introVolume ?: 100}%**")
                        append(" · Clip **${IntroClip.describe(intro.startMs, intro.endMs)}**")
                        append(" · ${if (isUrlIntro(intro)) "Link" else "Uploaded file"}")
                        if (!intro.enabled) append(" · **skipped on join**")
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

    private fun truncate(text: String, limit: Int = SELECT_TEXT_LIMIT): String =
        if (text.length <= limit) text else text.take(limit - 1).trimEnd() + "…"
}
