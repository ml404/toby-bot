package bot.toby.handler

import bot.toby.notify.ChannelMentions
import bot.toby.notify.NotificationRouter
import common.notification.PushAdapter
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
import common.notification.PushPayload
import database.service.AchievementService
import database.service.ConfigService
import database.service.UserNotificationPrefService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
        // Spy on a real router so `dispatch` runs its enforcement
        // (missing-supported-surface throws) and forwards to spied
        // primitives we can verify. The primitives are stubbed so we
        // don't drag in JDA REST for unit tests.
        val jda = mockk<JDA>(relaxed = true)
        val prefService = mockk<UserNotificationPrefService>(relaxed = true) {
            every { isOptedIn(any(), any(), any(), any()) } returns true
        }
        val configService = mockk<ConfigService>(relaxed = true)
        val pushAdapter = mockk<PushAdapter>(relaxed = true)
        router = spyk(NotificationRouter(jda, prefService, configService, pushAdapter))
        every { router.sendDm(any(), any(), any(), any()) } just runs
        every { router.sendPush(any(), any(), any(), any()) } just runs
        every {
            router.sendChannel(any(), any(), any(), any(), any(), any())
        } just runs
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
    fun `streak claim calls setProgress for every milestone with currentStreak as the value`() {
        handler.onStreakClaimed(
            StreakClaimedEvent(discordId, guildId, currentStreak = 7, longestStreak = 7, channelId = 99L)
        )
        verify(exactly = 1) { achievementService.unlock(discordId, guildId, "streak_first", 99L) }
        listOf(3, 7, 30, 100, 365).forEach { milestone ->
            verify(exactly = 1) {
                achievementService.setProgress(discordId, guildId, "streak_$milestone", 7L, 99L)
            }
        }
        // Old wiring used unlock() for milestones — guard against regression.
        verify(exactly = 0) { achievementService.unlock(discordId, guildId, "streak_3", any()) }
        verify(exactly = 0) { achievementService.unlock(discordId, guildId, "streak_7", any()) }
        verify(exactly = 0) { achievementService.unlock(discordId, guildId, "streak_30", any()) }
    }

    @Test
    fun `30-day streak claim still ratchets all milestones via setProgress`() {
        handler.onStreakClaimed(
            StreakClaimedEvent(discordId, guildId, currentStreak = 30, longestStreak = 30, channelId = null)
        )
        verify(exactly = 1) { achievementService.unlock(discordId, guildId, "streak_first", null) }
        listOf(3, 7, 30, 100, 365).forEach { milestone ->
            verify(exactly = 1) {
                achievementService.setProgress(discordId, guildId, "streak_$milestone", 30L, null)
            }
        }
    }

    @Test
    fun `streak claim propagates a decreased streak to setProgress (streak-broke case)`() {
        // User had a 14-day streak, broke it, now reclaiming at day 1.
        // The locked /achievements display should show 1/3, 1/7, 1/30 —
        // not the high-water mark. setProgress accepts decreases.
        handler.onStreakClaimed(
            StreakClaimedEvent(discordId, guildId, currentStreak = 1, longestStreak = 14, channelId = 99L)
        )
        listOf(3, 7, 30, 100, 365).forEach { milestone ->
            verify(exactly = 1) {
                achievementService.setProgress(discordId, guildId, "streak_$milestone", 1L, 99L)
            }
        }
    }

    // ---- level ----

    @Test
    fun `level-up calls setProgress for every level milestone with newLevel as the value`() {
        handler.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 4, newLevel = 26, totalXp = 0L, channelId = 99L)
        )
        // Unconditional — the service short-circuits already-owned milestones.
        listOf(5, 25, 50, 75, 100).forEach { milestone ->
            verify(exactly = 1) {
                achievementService.setProgress(discordId, guildId, "level_$milestone", 26L, 99L)
            }
        }
        // Old wiring used unlock() — guard against regression.
        verify(exactly = 0) { achievementService.unlock(any(), any(), match { it.startsWith("level_") }, any()) }
    }

    @Test
    fun `level-up forwards channelId on every setProgress call`() {
        handler.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 0, newLevel = 1, totalXp = 0L, channelId = 12345L)
        )
        listOf(5, 25, 50, 75, 100).forEach { milestone ->
            verify(exactly = 1) {
                achievementService.setProgress(discordId, guildId, "level_$milestone", 1L, 12345L)
            }
        }
    }

    @Test
    fun `level-up still ratchets progress on tiny single-level jumps`() {
        // Previously this test asserted no unlock calls. New semantics:
        // every level-up tells the service the absolute level — the
        // service decides whether to write/unlock.
        handler.onLevelUp(
            LevelUpEvent(discordId, guildId, oldLevel = 6, newLevel = 7, totalXp = 0L, channelId = 99L)
        )
        listOf(5, 25, 50, 75, 100).forEach { milestone ->
            verify(exactly = 1) {
                achievementService.setProgress(discordId, guildId, "level_$milestone", 7L, 99L)
            }
        }
    }

    // ---- tip / duel / lottery / intro / blackjack ----

    @Test
    fun `tip sent unlocks tip_giver and progresses every tips_sent tier for the sender`() {
        handler.onTipSent(TipSentEvent(senderDiscordId = discordId, recipientDiscordId = otherDiscordId, guildId = guildId, amount = 50L))
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "tip_giver")
        }
        listOf(10, 50).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(discordId, guildId, "tips_sent_$tier", 1L)
            }
        }
        // Recipient never gets credit on a tip — sender-only semantics.
        verify(exactly = 0) {
            achievementService.unlock(otherDiscordId, any(), any())
        }
        verify(exactly = 0) {
            achievementService.progress(otherDiscordId, any(), any(), any())
        }
    }

    @Test
    fun `duel resolution unlocks first_duel_win and progresses every duel_wins tier for the winner`() {
        handler.onDuelResolved(
            DuelResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 50L, pot = 100L,
            )
        )
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "first_duel_win")
        }
        listOf(10, 25, 50, 100).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(discordId, guildId, "duel_wins_$tier", 1L)
            }
        }
        // Winner doesn't get the consolation prize.
        verify(exactly = 0) {
            achievementService.progress(discordId, guildId, match { it.startsWith("duel_losses_") }, any())
        }
        // Loser doesn't get the winner achievements.
        verify(exactly = 0) {
            achievementService.unlock(otherDiscordId, any(), any())
        }
        verify(exactly = 0) {
            achievementService.progress(otherDiscordId, guildId, match { it.startsWith("duel_wins_") }, any())
        }
    }

    @Test
    fun `duel resolution progresses every duel_losses tier for the loser`() {
        handler.onDuelResolved(
            DuelResolvedEvent(
                winnerDiscordId = discordId, loserDiscordId = otherDiscordId,
                guildId = guildId, stake = 50L, pot = 100L,
            )
        )
        listOf(5, 25).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(otherDiscordId, guildId, "duel_losses_$tier", 1L)
            }
        }
    }

    @Test
    fun `lottery winner unlocks lottery_winner and progresses every lottery_wins tier`() {
        handler.onLotteryWon(LotteryWonEvent(discordId, guildId, amount = 500L))
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "lottery_winner")
        }
        listOf(3, 10, 25).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(discordId, guildId, "lottery_wins_$tier", 1L)
            }
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
    fun `blackjack natural unlocks blackjack_natural and progresses every blackjack_natural tier`() {
        // Regression guard: PR #493 originally shipped without this
        // listener; the event was published but nothing consumed it,
        // so the catalog entry stayed permanently locked even though
        // it was visible in the user-facing achievements list.
        handler.onBlackjackNatural(BlackjackNaturalEvent(discordId, guildId))
        verify(exactly = 1) {
            achievementService.unlock(discordId, guildId, "blackjack_natural")
        }
        listOf(5, 25).forEach { tier ->
            verify(exactly = 1) {
                achievementService.progress(discordId, guildId, "blackjack_natural_$tier", 1L)
            }
        }
    }

    // ---- voice ----

    @Test
    fun `voice session logged progresses every voice tier achievement by countedSeconds`() {
        handler.onVoiceSessionLogged(
            VoiceSessionLoggedEvent(discordId, guildId, countedSeconds = 3600L)
        )
        listOf("voice_10h", "voice_100h", "voice_250h", "voice_500h", "voice_1000h").forEach { code ->
            verify(exactly = 1) {
                achievementService.progress(discordId, guildId, code, 3600L)
            }
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
    fun `achievement unlock also routes a public shoutout via ACHIEVEMENT_SHOUTOUT with CHANNEL opt-in gating`() {
        val event = AchievementUnlockedEvent(
            discordId = discordId, guildId = guildId,
            achievementId = 1L, achievementCode = "tip_giver",
            name = "Generous", description = "desc", icon = null, channelId = null,
        )
        handler.onAchievementUnlocked(event)

        // The unlocker's mention is now in setContent (embed mentions
        // don't ping), so the router needs ChannelMentions to filter
        // the @-ping by per-user (ACHIEVEMENT_UNLOCK, CHANNEL) opt-in.
        verify(exactly = 1) {
            router.sendChannel(
                guildId = guildId,
                route = ChannelRouteKey.ACHIEVEMENT_SHOUTOUT,
                originChannelId = null,
                message = any(),
                onSent = null,
                mentions = ChannelMentions(
                    kind = NotificationChannelKind.ACHIEVEMENT_UNLOCK,
                    userIds = listOf(discordId),
                ),
            )
        }
    }

    @Test
    fun `achievement unlock also routes a push notification via ACHIEVEMENT_UNLOCK with PUSH opt-in gating`() {
        // Regression: pre-fix the handler only called sendDm/sendChannel,
        // so users who opted into Surface.PUSH for ACHIEVEMENT_UNLOCK got
        // nothing in their browser when an achievement fired.
        val event = AchievementUnlockedEvent(
            discordId = discordId, guildId = guildId,
            achievementId = 1L, achievementCode = "tip_giver",
            name = "Generous", description = "Tip another user for the first time.",
            icon = "🎁", channelId = null,
        )
        handler.onAchievementUnlocked(event)

        verify(exactly = 1) {
            router.sendPush(
                discordId = discordId,
                guildId = guildId,
                kind = NotificationChannelKind.ACHIEVEMENT_UNLOCK,
                message = any(),
            )
        }
    }

    @Test
    fun `achievement push payload carries the achievement name, description, and a null deep link when base url is unset`() {
        val event = AchievementUnlockedEvent(
            discordId = discordId, guildId = guildId,
            achievementId = 1L, achievementCode = "tip_giver",
            name = "Generous", description = "Tip another user for the first time.",
            icon = "🎁", channelId = null,
        )
        val captured = slot<() -> PushPayload>()
        every {
            router.sendPush(any(), any(), any(), capture(captured))
        } just runs

        handler.onAchievementUnlocked(event)

        val payload = captured.captured.invoke()
        assertEquals("🎁 Achievement unlocked — Generous", payload.title)
        assertEquals("Tip another user for the first time.", payload.body)
        assertNull(payload.deepLink) // setUp uses 2-arg ctor → webBaseUrl=""
    }

    @Test
    fun `achievement push payload sets deep link to profile page when app base url is configured`() {
        val handlerWithBaseUrl = AchievementEventHandler(
            achievementService = achievementService,
            notificationRouter = router,
            webBaseUrl = "https://www.toby-bot.co.uk",
        )
        val event = AchievementUnlockedEvent(
            discordId = discordId, guildId = guildId,
            achievementId = 1L, achievementCode = "tip_giver",
            name = "Generous", description = "desc", icon = null, channelId = null,
        )
        val captured = slot<() -> PushPayload>()
        every {
            router.sendPush(any(), any(), any(), capture(captured))
        } just runs

        handlerWithBaseUrl.onAchievementUnlocked(event)

        val payload = captured.captured.invoke()
        assertEquals("https://www.toby-bot.co.uk/profile/$guildId", payload.deepLink)
    }

    @Test
    fun `achievement push uses default rosette icon when event icon is null`() {
        val event = AchievementUnlockedEvent(
            discordId = discordId, guildId = guildId,
            achievementId = 1L, achievementCode = "x",
            name = "Quiet One", description = "d", icon = null, channelId = null,
        )
        val captured = slot<() -> PushPayload>()
        every {
            router.sendPush(any(), any(), any(), capture(captured))
        } just runs

        handler.onAchievementUnlocked(event)

        assert(captured.captured.invoke().title.startsWith("🏅 ")) {
            "expected default rosette icon when event.icon is null"
        }
    }

    @Test
    fun `achievement shoutout puts the mention in setContent so it actually pings`() {
        // Embed-mention pings are silent — moving the ping into the
        // message content is what makes the user's notification fire,
        // and what makes their CHANNEL opt-in toggle meaningful.
        val event = AchievementUnlockedEvent(
            discordId = discordId, guildId = guildId,
            achievementId = 1L, achievementCode = "tip_giver",
            name = "Generous", description = "desc", icon = null, channelId = null,
        )
        val captured = slot<() -> net.dv8tion.jda.api.utils.messages.MessageCreateData>()
        every {
            router.sendChannel(any(), any(), any(), capture(captured), any(), any())
        } just runs

        handler.onAchievementUnlocked(event)

        val payload = captured.captured.invoke()
        assert(payload.content == "<@$discordId>") {
            "expected setContent='<@$discordId>' to drive the ping, got '${payload.content}'"
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
