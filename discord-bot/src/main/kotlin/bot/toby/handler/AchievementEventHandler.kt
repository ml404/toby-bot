package bot.toby.handler

import bot.toby.notify.NotificationRouter
import common.events.AchievementUnlockedEvent
import common.events.BlackjackNaturalEvent
import common.events.DuelResolvedEvent
import common.events.IntroSetEvent
import common.events.LevelUpEvent
import common.events.LotteryWonEvent
import common.events.StreakClaimedEvent
import common.events.TipSentEvent
import common.events.VoiceSessionLoggedEvent
import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import database.service.AchievementService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.awt.Color

/**
 * Two responsibilities:
 *   1. Translate bot-wide engagement events ([StreakClaimedEvent],
 *      [LevelUpEvent]) into achievement-engine progress calls.
 *   2. Consume [AchievementUnlockedEvent] and surface the unlock —
 *      DM the user (gated by their [NotificationChannelKind.ACHIEVEMENT_UNLOCK]
 *      preference) and optionally post a public shoutout in the
 *      guild's configured achievement-announce channel.
 *
 * The set of "what events progress which codes" lives here because
 * adding a new achievement spec in [database.achievement.AchievementCatalog]
 * still needs a wiring touch — that pairing is the one bit a future
 * contributor will edit when adding a streak / level milestone.
 */
@Component
class AchievementEventHandler(
    private val achievementService: AchievementService,
    private val notificationRouter: NotificationRouter,
) {

    @EventListener
    fun onStreakClaimed(event: StreakClaimedEvent) {
        // First-time claim achievement is one-shot.
        achievementService.unlock(
            discordId = event.discordId,
            guildId = event.guildId,
            code = "streak_first",
            channelId = event.channelId
        )

        // Counter-style streak milestones. We pass the current streak
        // as the *absolute* value via a delta-from-current calculation
        // would be racy across multiple guilds with shared streaks —
        // here each is per-guild, so we just call unlock once the
        // threshold is reached. The catalog's threshold matches the
        // streak count, so we use `unlock` directly when the streak
        // crosses a known milestone.
        STREAK_MILESTONES.forEach { milestone ->
            if (event.currentStreak >= milestone) {
                achievementService.unlock(
                    discordId = event.discordId,
                    guildId = event.guildId,
                    code = "streak_$milestone",
                    channelId = event.channelId
                )
            }
        }
    }

    @EventListener
    fun onLevelUp(event: LevelUpEvent) {
        LEVEL_MILESTONES.forEach { milestone ->
            if (event.newLevel >= milestone && event.oldLevel < milestone) {
                achievementService.unlock(
                    discordId = event.discordId,
                    guildId = event.guildId,
                    code = "level_$milestone",
                    channelId = event.channelId
                )
            }
        }
    }

    @EventListener
    fun onTipSent(event: TipSentEvent) {
        // One-shot: the sender's first ever tip.
        achievementService.unlock(
            discordId = event.senderDiscordId,
            guildId = event.guildId,
            code = "tip_giver"
        )
    }

    @EventListener
    fun onDuelResolved(event: DuelResolvedEvent) {
        // Two-shot: the winner's first duel + their tenth.
        achievementService.unlock(
            discordId = event.winnerDiscordId,
            guildId = event.guildId,
            code = "first_duel_win"
        )
        achievementService.progress(
            discordId = event.winnerDiscordId,
            guildId = event.guildId,
            code = "duel_wins_10",
            delta = 1L
        )
    }

    @EventListener
    fun onLotteryWon(event: LotteryWonEvent) {
        achievementService.unlock(
            discordId = event.discordId,
            guildId = event.guildId,
            code = "lottery_winner"
        )
    }

    @EventListener
    fun onIntroSet(event: IntroSetEvent) {
        achievementService.unlock(
            discordId = event.discordId,
            guildId = event.guildId,
            code = "intro_set"
        )
    }

    @EventListener
    fun onBlackjackNatural(event: BlackjackNaturalEvent) {
        // One-shot: natural blackjack on the deal. BlackjackService
        // only publishes when `evaluate()` returns PLAYER_BLACKJACK,
        // which is guaranteed natural-on-the-deal (split-hand 21 and
        // post-hit 21 both return PLAYER_WIN instead).
        achievementService.unlock(
            discordId = event.discordId,
            guildId = event.guildId,
            code = "blackjack_natural"
        )
    }

    @EventListener
    fun onVoiceSessionLogged(event: VoiceSessionLoggedEvent) {
        // Counter-style: each session's countedSeconds adds to the
        // cumulative hours. progress() unlocks on threshold cross.
        achievementService.progress(
            discordId = event.discordId,
            guildId = event.guildId,
            code = "voice_10h",
            delta = event.countedSeconds
        )
        achievementService.progress(
            discordId = event.discordId,
            guildId = event.guildId,
            code = "voice_100h",
            delta = event.countedSeconds
        )
    }

    @EventListener
    fun onAchievementUnlocked(event: AchievementUnlockedEvent) {
        notificationRouter.sendDm(
            discordId = event.discordId,
            guildId = event.guildId,
            kind = NotificationChannelKind.ACHIEVEMENT_UNLOCK
        ) {
            val embed = EmbedBuilder()
                .setTitle("${event.icon ?: "🏅"} Achievement unlocked — ${event.name}")
                .setDescription(event.description)
                .setColor(Color(0xFFC857))
                .build()
            MessageCreateBuilder().setEmbeds(embed).build()
        }

        postPublicShoutout(event)
    }

    private fun postPublicShoutout(event: AchievementUnlockedEvent) {
        notificationRouter.sendChannel(
            guildId = event.guildId,
            route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT,
            message = {
                MessageCreateBuilder().setEmbeds(
                    EmbedBuilder()
                        .setTitle("${event.icon ?: "🏅"} Achievement unlocked")
                        .setDescription("<@${event.discordId}> unlocked **${event.name}** — ${event.description}")
                        .setColor(Color(0xFFC857))
                        .build()
                ).build()
            },
        )
    }

    companion object {
        private val STREAK_MILESTONES = listOf(3, 7, 30)
        private val LEVEL_MILESTONES = listOf(5, 25, 50)
    }
}
