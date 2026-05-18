package bot.toby.handler

import bot.toby.notify.ChannelMentions
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Coverage for [AchievementEventHandler]. The handler is the only thing
 * connecting publishers (`StreakClaimedEvent`, `LevelUpEvent`, `TipSentEvent`,
 * `DuelResolvedEvent`, `LotteryWonEvent`, `IntroSetEvent`,
 * `VoiceSessionLoggedEvent`, `BlackjackNaturalEvent`) to the achievement
 * engine. Forgetting an `@EventListener` here was a real bug in the
 * pre-merge draft of PR #493 (BlackjackService published the event but
 * AchievementEventHandler had no subscriber, so `blackjack_natural`
 * would never unlock). This test pins every wiring.
 */
class AchievementEventHandlerTest {

    private val discordId = 100L
    private val guildId = 42L
    private val otherDiscordId = 200L

    private lateinit var achievementService: AchievementService
    private lateinit var router: NotificationRouter
    private lateinit var handler: AchievementEventHandler

    @BeforeEach
    fun setup() {
        achievementService = mockk(relaxed = true)
        router = mockk(relaxed = true)
        handler = AchievementEventHandler(achievementService, router)
    }

    // ---- streak ----

    @Test
    fun `streak claim always unlocks streak_first`() {
        handler.onStreakClaimed(
            StreakClaimedEvent(discordId, guildId, currentStreak = 1, longestStreak = 1, channelId = 99L)
        )
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "streak_first", 99L)
        }
    }

    @Test
    fun `streak claim unlocks every milestone the current streak crosses`() {
        // currentStreak=7 → streak_first, streak_3, streak_7 (not streak_30).
        handler.onStreakClaimed(
            StreakClaimedEvent(discordId, guildId, currentStreak = 7, longestStreak = 7, channelId = 99L)
        )
        verify(exactly = 1) { achievementService.unlock(discordId, guildId, "streak_first", 99L) }
        verify(exactly = 1) { achievementService.unlock(discordId, guildId, "streak_3", 99L) }
        verify(exactly = 1) { achievementService.unlock(discordId, guildId, "streak_7", 99L) }
        verify(exactly = 0) { achievementService.unlock(discordId, guildId, "streak_30", 99L) }
    }

    @Test
    fun `30-day streak unlocks every streak milestone`() {
        handler.onStreakClaimed(
            StreakClaimedEvent(discordId, guildId, currentStreak = 30, longestStreak = 30, channelId = null)
        )
        listOf("streak_first", "streak_3", "streak_7", "streak_30").forEach { code ->
            verify(exactly = 1) { achievementService.unlock(discordId, guildId, code, null) }
        }
    }

    // ---- level ----

    @Test
    fun `level-up unlocks only milestones crossed in this jump`() {
        // 4 → 26 crosses 5 and 25, not 50.
        handler.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 4, newLevel = 26, totalXp = 0L, channelId = 99L)
        )
        verify(exactly = 1) { achievementService.unlock(discordId, guildId, "level_5", 99L) }
        verify(exactly = 1) { achievementService.unlock(discordId, guildId, "level_25", 99L) }
        verify(exactly = 0) { achievementService.unlock(discordId, guildId, "level_50", any()) }
    }

    @Test
    fun `level-up that doesn't cross any milestone unlocks nothing`() {
        handler.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 6, newLevel = 7, totalXp = 0L, channelId = 99L)
        )
        verify(exactly = 0) { achievementService.unlock(any(), any(), match { it.startsWith("level_") }, any()) }
    }

    // ---- tip / duel / lottery / intro / blackjack ----

    @Test
    fun `tip sent unlocks tip_giver for the sender`() {
        handler.onTipSent(TipSentEvent(senderDiscordId = discordId, recipientDiscordId = otherDiscordId, guildId = guildId, amount = 50L))
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "tip_giver")
        }
        verify(exactly = 0) {
            achievementService.unlock(otherDiscordId, any(), any())
        }
    }

    @Test
    fun `duel resolution unlocks first_duel_win and progresses duel_wins_10 for the winner`() {
        handler.onDuelResolved(
            DuelResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 50L, pot = 100L,
            )
        )
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "first_duel_win")
        }
        verify(exactly = 1) {
            achievementService.progress(discordId, guildId, "duel_wins_10", 1L)
        }
        // Loser gets nothing.
        verify(exactly = 0) {
            achievementService.unlock(otherDiscordId, any(), any())
        }
    }

    @Test
    fun `lottery winner unlocks lottery_winner for the recipient`() {
        handler.onLotteryWon(LotteryWonEvent(discordId, guildId, amount = 500L))
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "lottery_winner")
        }
    }

    @Test
    fun `intro set unlocks intro_set for the user`() {
        handler.onIntroSet(IntroSetEvent(discordId, guildId))
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "intro_set")
        }
    }

    @Test
    fun `blackjack natural unlocks blackjack_natural for the player`() {
        // Regression guard: PR #493 originally shipped without this
        // listener; the event was published but nothing consumed it,
        // so the catalog entry stayed permanently locked even though
        // it was visible in the user-facing achievements list.
        handler.onBlackjackNatural(BlackjackNaturalEvent(discordId, guildId))
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "blackjack_natural")
        }
    }

    // ---- voice ----

    @Test
    fun `voice session logged progresses both 10h and 100h achievements by countedSeconds`() {
        handler.onVoiceSessionLogged(
            VoiceSessionLoggedEvent(discordId, guildId, countedSeconds = 3600L)
        )
        verify(exactly = 1) {
            achievementService.progress(discordId, guildId, "voice_10h", 3600L)
        }
        verify(exactly = 1) {
            achievementService.progress(discordId, guildId, "voice_100h", 3600L)
        }
    }

    // ---- achievement unlock dispatch ----

    @Test
    fun `achievement unlock DMs via the router gated on ACHIEVEMENT_UNLOCK kind`() {
        val event = AchievementUnlockedEvent(
            discordId = discordId, guildId = guildId,
            achievementId = 1L, achievementCode = "tip_giver",
            name = "Generous", description = "Tip another user for the first time.",
            icon = "🎁", channelId = null,
        )
        handler.onAchievementUnlocked(event)

        // The router does the per-(kind, Surface.DM) opt-in check
        // internally — we only verify the DM-routing call here, kind +
        // user identity. (Surface.DM is implicit in sendDm.)
        verify(exactly = 1) {
            router.sendDm(
                discordId = discordId,
                guildId = guildId,
                kind = NotificationChannelKind.ACHIEVEMENT_UNLOCK,
                message = any(),
            )
        }
    }

    @Test
    fun `achievement unlock also routes a public shoutout via ACHIEVEMENT_SHOUTOUT`() {
        val event = AchievementUnlockedEvent(
            discordId = discordId, guildId = guildId,
            achievementId = 1L, achievementCode = "tip_giver",
            name = "Generous", description = "desc", icon = null, channelId = null,
        )
        handler.onAchievementUnlocked(event)

        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT,
                originChannelId = null,
                message = any(),
                onSent = null,
                mentions = null,
            )
        }
    }

    @Test
    fun `achievement shoutout does not pass mentions (embed mention is silent)`() {
        // The shoutout embeds `<@discordId>` in the embed description
        // — embed mentions don't ping by Discord rules, so there's no
        // user-ping suppression needed and the router shouldn't be
        // asked to filter.
        val event = AchievementUnlockedEvent(
            discordId = discordId, guildId = guildId,
            achievementId = 1L, achievementCode = "tip_giver",
            name = "Generous", description = "desc", icon = null, channelId = null,
        )
        handler.onAchievementUnlocked(event)

        verify(exactly = 0) {
            router.sendChannel(
                guildId = any(),
                route = any(),
                originChannelId = any(),
                message = any(),
                onSent = any(),
                mentions = match<ChannelMentions?> { it != null },
            )
        }
    }

    // ---- channelId propagation ----

    @Test
    fun `streak claim forwards channelId to achievement unlock so listeners can post in-channel`() {
        every {
            achievementService.unlock(any(), any(), any(), any())
        } returns AchievementService.ProgressResult(
            achievement = null, newProgress = 0L, unlocked = false, alreadyUnlocked = false,
        )
        handler.onStreakClaimed(
            StreakClaimedEvent(discordId, guildId, currentStreak = 1, longestStreak = 1, channelId = 12345L)
        )
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "streak_first", 12345L)
        }
    }
}
