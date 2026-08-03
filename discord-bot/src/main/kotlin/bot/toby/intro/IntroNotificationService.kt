package bot.toby.intro

import com.github.benmanes.caffeine.cache.Caffeine
import common.discord.embed
import common.logging.DiscordLogger
import common.notification.NotificationChannelKind
import common.notification.Surface
import database.service.user.UserNotificationPrefService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.User
import org.springframework.stereotype.Service
import java.awt.Color
import java.util.concurrent.TimeUnit

/**
 * Sends the "you don't have an intro yet" nudge when someone joins voice on a
 * server where they haven't set one.
 *
 * The nudge used to be a conversation: it asked the user to reply in the DM
 * with a URL or an attachment, armed a five-minute waiter to catch the reply,
 * and parsed whatever came back as free text. That flow was the weakest part
 * of the intro system:
 *
 *  - it guessed the volume out of the message body, which meant a link like
 *    `youtu.be/ID?t=42` silently set the intro to 42%;
 *  - it wrote slot 1 unconditionally, so an intro acquired between the prompt
 *    and the reply was overwritten;
 *  - it couldn't express a clip, a name, or which slot to use;
 *  - and it was useless five minutes later — including the DM itself, which
 *    deleted itself on the same timer.
 *
 * Now it's one message with a link to the guild's intro page, where all of
 * that is a form, and a pointer to `/setintro` for people who'd rather stay in
 * Discord. Nothing to parse, nothing to time out, and the DM keeps working
 * whenever the user gets round to it.
 */
@Service
class IntroNotificationService(
    // Nullable + default null so legacy callers / unit tests that construct
    // this service manually keep compiling. Null = no gate (matches
    // pre-refactor behaviour). Spring autowires the real bean in production.
    private val notificationPrefService: UserNotificationPrefService? = null,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    /** (guild, user) pairs nudged inside [PROMPT_COOLDOWN_HOURS]. */
    private val recentlyPrompted = Caffeine.newBuilder()
        .expireAfterWrite(PROMPT_COOLDOWN_HOURS, TimeUnit.HOURS)
        .maximumSize(10_000)
        .build<String, Boolean>()

    fun promptUserForMusicInfo(user: User, guild: Guild) {
        logger.setGuildAndUserContext(guild, user)
        // Honour the per-user INTRO_PROMPT opt-out. Default is opt-in so
        // existing servers keep nudging new members; users who don't want
        // intro prompts run `/notify set INTRO_PROMPT off` and we silently
        // skip the DM. Pref service is nullable for legacy/test callers —
        // null means "no gate".
        val gateActive = notificationPrefService
            ?.isOptedIn(user.idLong, guild.idLong, NotificationChannelKind.INTRO_PROMPT, Surface.DM)
            ?: true
        if (!gateActive) {
            logger.info { "User opted out of INTRO_PROMPT; skipping prompt for guild '${guild.name}'." }
            return
        }
        // The caller fires on every voice join while the user has no intro, so
        // without this a channel-hopper got a DM per hop. One nudge a day is a
        // nudge; ten in an evening is why people mute the bot.
        val promptKey = "${guild.idLong}:${user.idLong}"
        if (recentlyPrompted.getIfPresent(promptKey) != null) {
            logger.info { "Already prompted this user for an intro recently; skipping." }
            return
        }
        recentlyPrompted.put(promptKey, true)

        logger.info { "Prompting user to set an intro on '${guild.name}'" }
        user.openPrivateChannel().queue { channel ->
            channel.sendMessageEmbeds(promptEmbed(guild))
                .setComponents(
                    ActionRow.of(IntroPresenter.webDashboardButton(guild.idLong, "Set up my intro"))
                )
                // Deliberately not auto-deleted: the whole point is that it
                // still works when the user comes back to it later.
                .queue()
        }
    }

    private fun promptEmbed(guild: Guild) = embed(
        title = "You don't have an intro on ${guild.name}",
        color = INTRO_COLOR,
        description = "Pick a track and TobyBot plays it when you join a voice channel there.\n\n" +
            "Set one up on the web — you can trim it to a clip and hear it before saving — " +
            "or run `/setintro` in the server.",
    ) {
        setFooter("Don't want these? Run /notify set INTRO_PROMPT off")
    }

    companion object {
        /**
         * How long to leave a user alone after nudging them for an intro.
         * In-memory, so a redeploy may cost someone one extra nudge — much
         * cheaper than persisting it.
         */
        const val PROMPT_COOLDOWN_HOURS = 24L

        private val INTRO_COLOR: Color = Color(88, 101, 242) // Discord blurple
    }
}
