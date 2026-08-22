package bot.toby.intro

import bot.toby.notify.NotificationRouter
import common.discord.embed
import common.intro.IntroHealth
import common.logging.DiscordLogger
import common.notification.NotificationChannelKind
import database.dto.music.MusicDto
import database.service.music.MusicFileService
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.awt.Color
import java.time.Instant

/**
 * Keeps each intro row's health and play history up to date, and tells the
 * owner when one of theirs has stopped working.
 *
 * The gap this closes: intros play from the voice-join path, which has no
 * interaction to reply to, so `PlayerManager`'s failure branches were writing
 * to a null hook. A YouTube link that had been deleted, region-locked or
 * age-gated produced silence on every join, indefinitely, while `/listintros`
 * and the web page both still described it as fine. The only evidence was a
 * server log nobody reads.
 *
 * A play is what makes a failure count "consecutive", so the two halves have
 * to agree on what counts as a play. They now both mean audio that actually
 * reached the listener: an intro whose source resolved and then died
 * mid-stream used to arrive here as a *success*, resetting the very counter
 * that was supposed to notice it.
 */
@Service
class IntroHealthService(
    private val musicFileService: MusicFileService,
    private val notificationRouter: NotificationRouter? = null,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @EventListener
    fun onIntroPlayed(event: IntroPlayedEvent) {
        update(event.introId) { dto ->
            dto.playCount += 1
            dto.lastPlayedAt = Instant.now()
            // A success is what makes the failure count "consecutive". Without
            // this reset a link that broke for a week in 2024 would still be
            // flagged today.
            dto.failureCount = 0
            dto.lastFailureReason = null
        }
    }

    @EventListener
    fun onIntroFailed(event: IntroFailedEvent) {
        val reason = IntroHealth.normaliseReason(event.reason)
        val updated = update(event.introId) { dto ->
            dto.failureCount += 1
            dto.lastFailureAt = Instant.now()
            dto.lastFailureReason = reason
        } ?: return

        // Notify on the crossing only. Nagging every join would make the
        // notification worthless faster than the broken intro does.
        if (updated.failureCount == IntroHealth.UNHEALTHY_AFTER_FAILURES) {
            notifyOwner(updated, reason)
        }
    }

    @EventListener
    fun onIntroLoudnessMeasured(event: IntroLoudnessMeasuredEvent) {
        update(event.introId) { dto -> dto.measuredRms = event.rms }
    }

    private fun update(introId: String, mutate: (MusicDto) -> Unit): MusicDto? =
        runCatching {
            val dto = musicFileService.getMusicFileById(introId)
            if (dto == null) {
                // Normal, not alarming: the owner can delete an intro between
                // it being queued and its outcome arriving.
                logger.info { "Intro $introId no longer exists; dropping its playback outcome." }
                return@runCatching null
            }
            mutate(dto)
            musicFileService.updateMusicFile(dto)
            dto
        }.onFailure {
            // Called from event handlers on the audio and load paths — a throw
            // here must not take playback with it.
            logger.error("Failed to record playback outcome for intro $introId: ${it.message}")
        }.getOrNull()

    private fun notifyOwner(dto: MusicDto, reason: String) {
        val owner = dto.userDto ?: return
        val router = notificationRouter ?: return
        val name = IntroPresenter.displayName(dto)
        logger.info { "Intro ${dto.id} ('$name') has failed ${dto.failureCount} times; notifying owner." }
        runCatching {
            router.sendDm(owner.discordId, owner.guildId, NotificationChannelKind.INTRO_BROKEN) {
                MessageCreateBuilder()
                    .setEmbeds(brokenEmbed(name, dto, reason))
                    .setComponents(ActionRow.of(IntroPresenter.webDashboardButton(owner.guildId, "Fix my intro")))
                    .build()
            }
        }.onFailure {
            logger.error("Failed to DM owner about broken intro ${dto.id}: ${it.message}")
        }
    }

    private fun brokenEmbed(name: String, dto: MusicDto, reason: String) = embed(
        title = "Your intro isn't playing",
        color = BROKEN_COLOR,
        description = "**$name** (slot #${dto.index ?: '?'}) has failed " +
            "${dto.failureCount} times in a row, so nothing plays when you join voice.\n\n" +
            "> $reason\n\n" +
            "This usually means the video was deleted, made private, age-gated or " +
            "blocked in the bot's region. Replacing the link fixes it.",
    ) {
        setFooter("Don't want these? Run /notify set INTRO_BROKEN off")
    }

    companion object {
        private val BROKEN_COLOR: Color = Color(237, 66, 69) // Discord red
    }
}
