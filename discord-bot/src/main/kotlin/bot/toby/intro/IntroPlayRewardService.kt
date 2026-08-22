package bot.toby.intro

import bot.toby.handler.VoiceEventHandler
import common.logging.DiscordLogger
import database.service.leveling.XpAwardService
import database.service.social.SocialCreditAwardService
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/**
 * Pays the intro-play credit and XP, once the intro has actually played.
 *
 * The join handler used to pay the moment it had asked for an intro, which
 * meant a dead link earned exactly what a clip everybody heard earned. Since
 * [IntroPlayedEvent] now fires only after audio has run, settling here pays
 * for what happened rather than for what was attempted.
 *
 * Only plays a join asked for are paid: [IntroRewardLedger] is written by the
 * join path alone, so pressing the **View intros** play button — or running
 * `/play intro` — earns nothing, however many times it is pressed.
 */
@Service
class IntroPlayRewardService(
    private val ledger: IntroRewardLedger,
    private val awardService: SocialCreditAwardService,
    private val xpAwardService: XpAwardService,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @EventListener
    fun onIntroPlayed(event: IntroPlayedEvent) {
        if (!ledger.redeem(event.introId)) return
        val guildId = IntroOwnership.guildIdOf(event.introId) ?: return
        val discordId = IntroOwnership.discordIdOf(event.introId) ?: return

        // Best-effort, like every other listener on this path: an award that
        // throws must not travel back into playback.
        runCatching {
            awardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = VoiceEventHandler.INTRO_PLAY_CREDIT,
                reason = "intro-play",
            )
            xpAwardService.award(
                discordId = discordId,
                guildId = guildId,
                amount = VoiceEventHandler.INTRO_PLAY_XP,
                reason = "intro-play",
            )
        }.onFailure {
            logger.error("Failed to award the intro play for ${event.introId}: ${it.message}")
        }
    }
}
