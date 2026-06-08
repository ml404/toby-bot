package bot.toby.handler

import bot.toby.notify.ChannelMentions
import bot.toby.notify.NotificationRouter
import common.events.user.AchievementUnlockedEvent
import common.events.casino.baccarat.BaccaratWonEvent
import common.events.casino.blackjack.BlackjackNaturalEvent
import common.events.casino.casinoholdem.CasinoHoldemWonEvent
import common.events.casino.CasinoGamePlayedEvent
import common.events.casino.coinflip.CoinflipWonEvent
import common.events.casino.dice.DiceWonEvent
import common.events.pvp.duel.DuelResolvedEvent
import common.events.casino.highlow.HighlowHandResolvedEvent
import common.events.casino.horseracing.HorseRacingWonEvent
import common.events.user.IntroSetEvent
import common.events.casino.keno.KenoPerfectEvent
import common.events.leveling.LevelUpEvent
import common.events.lottery.LotteryWonEvent
import common.events.casino.plinko.PlinkoJackpotEvent
import common.events.casino.poker.PokerRoyalFlushEvent
import common.events.casino.roulette.RouletteStraightWinEvent
import common.events.pvp.connect4.Connect4ResolvedEvent
import common.events.pvp.rps.RpsResolvedEvent
import common.events.pvp.tictactoe.TicTacToeResolvedEvent
import common.events.casino.scratch.ScratchJackpotEvent
import common.events.casino.slots.SlotsJackpotEvent
import common.events.social.StreakClaimedEvent
import common.events.social.TipSentEvent
import common.events.activity.VoiceSessionLoggedEvent
import common.events.casino.wheeloffortune.WheelJackpotEvent
import common.notification.ChannelRouteKey
import common.notification.NotificationChannelKind
import common.notification.PushPayload
import database.service.guild.AchievementService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.springframework.beans.factory.annotation.Value
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
    @Value("\${app.base-url:}") private val webBaseUrl: String = "",
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

        // Milestone streaks ratchet by absolute value so the locked
        // /achievements display reflects the user's current streak,
        // not zero. setProgress is idempotent post-unlock and accepts
        // decreases (streak-broke).
        STREAK_MILESTONES.forEach { milestone ->
            achievementService.setProgress(
                discordId = event.discordId,
                guildId = event.guildId,
                code = "streak_$milestone",
                value = event.currentStreak.toLong(),
                channelId = event.channelId
            )
        }
    }

    @EventListener
    fun onLevelUp(event: LevelUpEvent) {
        LEVEL_MILESTONES.forEach { milestone ->
            achievementService.setProgress(
                discordId = event.discordId,
                guildId = event.guildId,
                code = "level_$milestone",
                value = event.newLevel.toLong(),
                channelId = event.channelId
            )
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
        TIP_SENT_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.senderDiscordId,
                guildId = event.guildId,
                code = "tips_sent_$tier",
                delta = 1L
            )
        }
    }

    @EventListener
    fun onDuelResolved(event: DuelResolvedEvent) {
        achievementService.unlock(
            discordId = event.winnerDiscordId,
            guildId = event.guildId,
            code = "first_duel_win"
        )
        DUEL_WIN_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.winnerDiscordId,
                guildId = event.guildId,
                code = "duel_wins_$tier",
                delta = 1L
            )
        }
        DUEL_LOSS_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.loserDiscordId,
                guildId = event.guildId,
                code = "duel_losses_$tier",
                delta = 1L
            )
        }
    }

    @EventListener
    fun onRpsResolved(event: RpsResolvedEvent) {
        achievementService.unlock(
            discordId = event.winnerDiscordId,
            guildId = event.guildId,
            code = "first_rps_win"
        )
        RPS_WIN_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.winnerDiscordId,
                guildId = event.guildId,
                code = "rps_wins_$tier",
                delta = 1L
            )
        }
        RPS_LOSS_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.loserDiscordId,
                guildId = event.guildId,
                code = "rps_losses_$tier",
                delta = 1L
            )
        }
    }

    @EventListener
    fun onTicTacToeResolved(event: TicTacToeResolvedEvent) {
        achievementService.unlock(
            discordId = event.winnerDiscordId,
            guildId = event.guildId,
            code = "first_tictactoe_win"
        )
        TICTACTOE_WIN_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.winnerDiscordId,
                guildId = event.guildId,
                code = "tictactoe_wins_$tier",
                delta = 1L
            )
        }
        TICTACTOE_LOSS_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.loserDiscordId,
                guildId = event.guildId,
                code = "tictactoe_losses_$tier",
                delta = 1L
            )
        }
    }

    @EventListener
    fun onConnect4Resolved(event: Connect4ResolvedEvent) {
        achievementService.unlock(
            discordId = event.winnerDiscordId,
            guildId = event.guildId,
            code = "first_connect4_win"
        )
        CONNECT4_WIN_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.winnerDiscordId,
                guildId = event.guildId,
                code = "connect4_wins_$tier",
                delta = 1L
            )
        }
        CONNECT4_LOSS_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.loserDiscordId,
                guildId = event.guildId,
                code = "connect4_losses_$tier",
                delta = 1L
            )
        }
    }

    @EventListener
    fun onLotteryWon(event: LotteryWonEvent) {
        achievementService.unlock(
            discordId = event.discordId,
            guildId = event.guildId,
            code = "lottery_winner"
        )
        LOTTERY_WIN_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.discordId,
                guildId = event.guildId,
                code = "lottery_wins_$tier",
                delta = 1L
            )
        }
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
        BLACKJACK_NATURAL_TIERS.forEach { tier ->
            achievementService.progress(
                discordId = event.discordId,
                guildId = event.guildId,
                code = "blackjack_natural_$tier",
                delta = 1L
            )
        }
    }

    @EventListener
    fun onSlotsJackpot(event: SlotsJackpotEvent) {
        achievementService.unlock(event.discordId, event.guildId, "slots_first_jackpot")
    }

    @EventListener
    fun onRouletteStraightWin(event: RouletteStraightWinEvent) {
        achievementService.unlock(event.discordId, event.guildId, "roulette_first_straight_win")
    }

    @EventListener
    fun onPokerRoyalFlush(event: PokerRoyalFlushEvent) {
        achievementService.unlock(event.discordId, event.guildId, "poker_first_royal_flush")
    }

    @EventListener
    fun onDiceWon(event: DiceWonEvent) {
        achievementService.unlock(event.discordId, event.guildId, "dice_first_win")
    }

    @EventListener
    fun onCoinflipWon(event: CoinflipWonEvent) {
        achievementService.unlock(event.discordId, event.guildId, "coinflip_first_win")
    }

    @EventListener
    fun onCasinoGamePlayed(event: CasinoGamePlayedEvent) {
        achievementService.unlock(event.discordId, event.guildId, "casino_first_game")
    }

    @EventListener
    fun onKenoPerfect(event: KenoPerfectEvent) {
        achievementService.unlock(event.discordId, event.guildId, "keno_first_perfect")
    }

    @EventListener
    fun onPlinkoJackpot(event: PlinkoJackpotEvent) {
        achievementService.unlock(event.discordId, event.guildId, "plinko_first_jackpot")
    }

    @EventListener
    fun onScratchJackpot(event: ScratchJackpotEvent) {
        achievementService.unlock(event.discordId, event.guildId, "scratch_first_jackpot")
    }

    @EventListener
    fun onWheelJackpot(event: WheelJackpotEvent) {
        achievementService.unlock(event.discordId, event.guildId, "wheel_first_jackpot")
    }

    @EventListener
    fun onHorseRacingWon(event: HorseRacingWonEvent) {
        achievementService.unlock(event.discordId, event.guildId, "horse_racing_first_win")
    }

    @EventListener
    fun onBaccaratWon(event: BaccaratWonEvent) {
        achievementService.unlock(event.discordId, event.guildId, "baccarat_first_win")
    }

    @EventListener
    fun onCasinoHoldemWon(event: CasinoHoldemWonEvent) {
        achievementService.unlock(event.discordId, event.guildId, "casino_holdem_first_win")
    }

    @EventListener
    fun onHighlowHandResolved(event: HighlowHandResolvedEvent) {
        // Streak counter lives in achievement_progress: progress(+1) on
        // win cascades to unlock at 5, setProgress(0) on loss resets.
        // Both calls are no-ops once the achievement is unlocked.
        if (event.isWin) {
            achievementService.progress(event.discordId, event.guildId, "highlow_first_streak", delta = 1L)
        } else {
            achievementService.setProgress(event.discordId, event.guildId, "highlow_first_streak", value = 0L)
        }
    }

    @EventListener
    fun onVoiceSessionLogged(event: VoiceSessionLoggedEvent) {
        // Counter-style: each session's countedSeconds adds to the
        // cumulative hours. progress() unlocks on threshold cross.
        VOICE_TIER_CODES.forEach { code ->
            achievementService.progress(
                discordId = event.discordId,
                guildId = event.guildId,
                code = code,
                delta = event.countedSeconds
            )
        }
    }

    @EventListener
    fun onAchievementUnlocked(event: AchievementUnlockedEvent) {
        notificationRouter.dispatch(
            kind = NotificationChannelKind.ACHIEVEMENT_UNLOCK,
            discordId = event.discordId,
            guildId = event.guildId,
        ) {
            dm {
                val embed = EmbedBuilder()
                    .setTitle("${event.icon ?: "🏅"} Achievement unlocked — ${event.name}")
                    .setDescription(event.description)
                    .setColor(Color(0xFFC857))
                    .build()
                MessageCreateBuilder().setEmbeds(embed).build()
            }
            push {
                PushPayload(
                    title = "${event.icon ?: "🏅"} Achievement unlocked — ${event.name}",
                    body = event.description,
                    deepLink = webBaseUrl.takeIf { it.isNotBlank() }
                        ?.let { "$it/profile/${event.guildId}" },
                )
            }
            channel(
                route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT,
                // Router suppresses the unlocker's user-ping when they've
                // opted out of (ACHIEVEMENT_UNLOCK, CHANNEL). The shoutout
                // post still happens; they just don't get notified.
                mentions = ChannelMentions(
                    kind = NotificationChannelKind.ACHIEVEMENT_UNLOCK,
                    userIds = listOf(event.discordId),
                ),
            ) {
                // setContent on the message (not just the embed description) so the
                // <@unlocker> mention actually pings — embed-mention pings are silent.
                MessageCreateBuilder()
                    .setEmbeds(
                        EmbedBuilder()
                            .setTitle("${event.icon ?: "🏅"} Achievement unlocked")
                            .setDescription("<@${event.discordId}> unlocked **${event.name}** — ${event.description}")
                            .setColor(Color(0xFFC857))
                            .build()
                    )
                    .setContent("<@${event.discordId}>")
                    .build()
            }
        }
    }

    companion object {
        private val STREAK_MILESTONES = listOf(3, 7, 30, 100, 365)
        private val LEVEL_MILESTONES = listOf(5, 25, 50, 75, 100)
        private val DUEL_WIN_TIERS = listOf(10, 25, 50, 100)
        private val DUEL_LOSS_TIERS = listOf(5, 25)
        private val RPS_WIN_TIERS = listOf(10, 25)
        private val RPS_LOSS_TIERS = listOf(5)
        private val TICTACTOE_WIN_TIERS = listOf(10, 25)
        private val TICTACTOE_LOSS_TIERS = listOf(5)
        private val CONNECT4_WIN_TIERS = listOf(10, 25)
        private val CONNECT4_LOSS_TIERS = listOf(5)
        private val LOTTERY_WIN_TIERS = listOf(3, 10, 25)
        private val BLACKJACK_NATURAL_TIERS = listOf(5, 25)
        private val TIP_SENT_TIERS = listOf(10, 50)
        private val VOICE_TIER_CODES = listOf(
            "voice_10h", "voice_100h", "voice_250h", "voice_500h", "voice_1000h"
        )
    }
}
