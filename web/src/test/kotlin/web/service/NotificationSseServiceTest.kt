package web.service

import common.events.user.AchievementUnlockedEvent
import common.events.leveling.LevelUpEvent
import common.events.lottery.LotteryDrawnForTicketHolderEvent
import common.events.social.TipSentEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import web.service.sse.KeyedSseRegistry

/**
 * Pure translation/routing tests — every event listener's job is to
 * compose an SSE payload and call `registry.fanOut`. We mock the
 * registry and assert on the call arguments. Registry plumbing itself
 * is covered by `KeyedSseRegistryTest`.
 */
class NotificationSseServiceTest {

    private lateinit var registry: KeyedSseRegistry<Long>
    private lateinit var service: NotificationSseService

    @BeforeEach
    fun setUp() {
        registry = mockk(relaxed = true)
        service = NotificationSseService(registry)
    }

    // ---- register delegates to the underlying registry ----

    @Test
    fun `register delegates to the registry with the discordId payload`() {
        val emitter = mockk<SseEmitter>(relaxed = true)
        every { registry.register(123L, any(), any()) } returns emitter
        val returned = service.register(123L)
        assertNotNull(returned)
        verify(exactly = 1) { registry.register(123L, mapOf("discordId" to 123L), any()) }
    }

    // ---- AchievementUnlockedEvent ----

    @Test
    fun `onAchievementUnlocked fans out an achievement event to the unlocker only`() {
        val event = AchievementUnlockedEvent(
            discordId = 1L,
            guildId = 999L,
            achievementId = 5L,
            achievementCode = "streak_7",
            name = "Week-long streak",
            description = "Claim your daily 7 days in a row.",
            icon = "🔥",
            channelId = null,
        )
        val payload = slot<Any>()
        every {
            registry.fanOut(1L, NotificationSseService.ACHIEVEMENT_EVENT, capture(payload))
        } returns Unit

        service.onAchievementUnlocked(event)

        verify(exactly = 1) {
            registry.fanOut(1L, NotificationSseService.ACHIEVEMENT_EVENT, any())
        }
        @Suppress("UNCHECKED_CAST")
        val map = payload.captured as Map<String, Any>
        assertEquals("🔥 Achievement unlocked — Week-long streak", map["title"])
        assertEquals("Claim your daily 7 days in a row.", map["body"])
        assertEquals("/profile/999", map["deepLink"])
        assertEquals("success", map["type"])
    }

    @Test
    fun `onAchievementUnlocked uses default icon when the achievement has no icon`() {
        val event = AchievementUnlockedEvent(
            discordId = 1L,
            guildId = 999L,
            achievementId = 5L,
            achievementCode = "x",
            name = "Iconless",
            description = "no icon",
            icon = null,
            channelId = null,
        )
        val payload = slot<Any>()
        every {
            registry.fanOut(any(), any(), capture(payload))
        } returns Unit

        service.onAchievementUnlocked(event)

        @Suppress("UNCHECKED_CAST")
        val map = payload.captured as Map<String, Any>
        assertEquals(
            "${NotificationSseService.DEFAULT_ACHIEVEMENT_ICON} Achievement unlocked — Iconless",
            map["title"],
        )
    }

    // ---- LevelUpEvent ----

    @Test
    fun `onLevelUp fans out a levelUp event to the user with the new level in the title`() {
        val event = LevelUpEvent(
            discordId = 7L,
            guildId = 100L,
            oldLevel = 4,
            newLevel = 5,
            totalXp = 12345L,
            channelId = null,
        )
        val payload = slot<Any>()
        every {
            registry.fanOut(7L, NotificationSseService.LEVEL_UP_EVENT, capture(payload))
        } returns Unit

        service.onLevelUp(event)

        verify(exactly = 1) {
            registry.fanOut(7L, NotificationSseService.LEVEL_UP_EVENT, any())
        }
        @Suppress("UNCHECKED_CAST")
        val map = payload.captured as Map<String, Any>
        assertEquals("Level 5!", map["title"])
        assertEquals("/profile/100", map["deepLink"])
        assertEquals("success", map["type"])
    }

    // ---- TipSentEvent ----

    @Test
    fun `onTipSent fans out a tip event to the recipient only and includes the amount`() {
        val event = TipSentEvent(
            senderDiscordId = 1L,
            recipientDiscordId = 2L,
            guildId = 200L,
            amount = 500L,
        )
        val payload = slot<Any>()
        every {
            registry.fanOut(2L, NotificationSseService.TIP_EVENT, capture(payload))
        } returns Unit

        service.onTipSent(event)

        verify(exactly = 1) {
            registry.fanOut(2L, NotificationSseService.TIP_EVENT, any())
        }
        // The sender must NOT receive a tip toast — they triggered the action.
        verify(exactly = 0) {
            registry.fanOut(1L, NotificationSseService.TIP_EVENT, any())
        }
        @Suppress("UNCHECKED_CAST")
        val map = payload.captured as Map<String, Any>
        assertEquals("+500 credits", map["title"])
        assertEquals("/profile/200", map["deepLink"])
    }

    // ---- LotteryDrawnForTicketHolderEvent ----

    @Test
    fun `onLotteryDrawnForTicketHolder uses the win title and amount when didWin`() {
        val event = LotteryDrawnForTicketHolderEvent(
            discordId = 10L,
            guildId = 300L,
            didWin = true,
            amountWon = 1500L,
        )
        val payload = slot<Any>()
        every {
            registry.fanOut(10L, NotificationSseService.LOTTERY_DRAWN_EVENT, capture(payload))
        } returns Unit

        service.onLotteryDrawnForTicketHolder(event)

        @Suppress("UNCHECKED_CAST")
        val map = payload.captured as Map<String, Any>
        assertEquals("🎰 You won the lottery!", map["title"])
        assertTrue((map["body"] as String).contains("1500"))
        assertEquals(true, map["didWin"])
        assertEquals(1500L, map["amountWon"])
        assertEquals("/profile/300", map["deepLink"])
        assertEquals("success", map["type"])
    }

    @Test
    fun `onLotteryDrawnForTicketHolder uses the consolation title when didWin is false`() {
        val event = LotteryDrawnForTicketHolderEvent(
            discordId = 10L,
            guildId = 300L,
            didWin = false,
            amountWon = 0L,
        )
        val payload = slot<Any>()
        every {
            registry.fanOut(10L, NotificationSseService.LOTTERY_DRAWN_EVENT, capture(payload))
        } returns Unit

        service.onLotteryDrawnForTicketHolder(event)

        @Suppress("UNCHECKED_CAST")
        val map = payload.captured as Map<String, Any>
        assertEquals("🎟️ Lottery drew", map["title"])
        assertEquals(false, map["didWin"])
        assertEquals("info", map["type"])
    }

    // ---- cross-user isolation ----

    @Test
    fun `events for one user do not bleed into another user's bucket`() {
        // Verifying that the keying matches event.discordId / recipientDiscordId
        // is the whole point of the per-user channel.
        service.onAchievementUnlocked(
            AchievementUnlockedEvent(
                discordId = 1L, guildId = 1L, achievementId = 1L,
                achievementCode = "x", name = "A", description = "d",
                icon = null, channelId = null,
            ),
        )
        service.onLevelUp(LevelUpEvent(2L, 2L, 1, 2, 100L, null))
        service.onTipSent(TipSentEvent(3L, 4L, 4L, 100L))
        service.onLotteryDrawnForTicketHolder(
            LotteryDrawnForTicketHolderEvent(5L, 5L, true, 250L),
        )

        verify(exactly = 1) {
            registry.fanOut(1L, NotificationSseService.ACHIEVEMENT_EVENT, any())
        }
        verify(exactly = 1) {
            registry.fanOut(2L, NotificationSseService.LEVEL_UP_EVENT, any())
        }
        // Tip goes to recipient (4L), not sender (3L).
        verify(exactly = 1) {
            registry.fanOut(4L, NotificationSseService.TIP_EVENT, any())
        }
        verify(exactly = 0) {
            registry.fanOut(3L, NotificationSseService.TIP_EVENT, any())
        }
        verify(exactly = 1) {
            registry.fanOut(5L, NotificationSseService.LOTTERY_DRAWN_EVENT, any())
        }
    }

    // ---- heartbeat ----

    @Test
    fun `heartbeat delegates to the registry`() {
        service.heartbeat()
        verify(exactly = 1) { registry.heartbeat() }
    }
}
