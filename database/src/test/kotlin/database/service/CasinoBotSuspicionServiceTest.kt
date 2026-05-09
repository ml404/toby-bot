package database.service

import common.events.AntiAutoclickEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

class CasinoBotSuspicionServiceTest {

    private lateinit var service: CasinoBotSuspicionService
    private lateinit var eventPublisher: ApplicationEventPublisher

    private val discordId = 100L
    private val guildId = 200L
    private val game = "coinflip"

    @BeforeEach
    fun setup() {
        eventPublisher = mockk(relaxed = true)
        service = CasinoBotSuspicionService(eventPublisher)
    }

    @Test
    fun `first ever bet has streak 0`() {
        // No prior state → no signal that this is bot-like, regardless of
        // what the client supplied. Streak only grows on the *second* and
        // subsequent same-spot, no-motion clicks.
        val streak = service.recordAndScore(discordId, guildId, game,
            clickX = 100, clickY = 200, mouseMoved = false)

        assertEquals(0, streak)
    }

    @Test
    fun `same spot with no movement increments streak across consecutive bets`() {
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        val s2 = service.recordAndScore(discordId, guildId, game, 100, 200, false)
        val s3 = service.recordAndScore(discordId, guildId, game, 100, 200, false)
        val s4 = service.recordAndScore(discordId, guildId, game, 100, 200, false)

        assertEquals(1, s2)
        assertEquals(2, s3)
        assertEquals(3, s4)
    }

    @Test
    fun `pixel jitter inside the epsilon still counts as same spot`() {
        // Each click is compared against the IMMEDIATELY PRIOR click (not
        // the first click), so a sustained autoclicker that jitters by
        // ±EPSILON_PX every time still climbs the streak.
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        val s2 = service.recordAndScore(discordId, guildId, game, 101, 200, false)   // dx=1
        val s3 = service.recordAndScore(discordId, guildId, game, 100, 202, false)   // dx=1, dy=2
        val s4 = service.recordAndScore(discordId, guildId, game, 102, 200, false)   // dx=2, dy=2

        assertEquals(1, s2)
        assertEquals(2, s3)
        assertEquals(3, s4, "all consecutive distances within EPSILON_PX=2")
    }

    @Test
    fun `cumulative drift outside the epsilon still resets when a single hop exceeds it`() {
        // Comparison is to the most recent click, not the origin. A 5-pixel
        // jump in one bet resets even if all earlier clicks were tightly
        // clustered around the origin.
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        val resetByOneHop = service.recordAndScore(discordId, guildId, game, 100, 205, false)
        assertEquals(0, resetByOneHop)
    }

    @Test
    fun `click outside the epsilon resets the streak`() {
        // Build up a streak, then click far away — fresh slate.
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        val reset = service.recordAndScore(discordId, guildId, game, 500, 600, false)

        assertEquals(0, reset)
    }

    @Test
    fun `mouseMoved true resets the streak even at the same pixel`() {
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        val reset = service.recordAndScore(discordId, guildId, game, 100, 200, mouseMoved = true)

        assertEquals(0, reset, "natural cursor motion before a same-spot click clears suspicion")
    }

    @Test
    fun `null signals reset the streak (Discord, keyboard submit, broken client)`() {
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)

        val nullClickX = service.recordAndScore(discordId, guildId, game, null, 200, false)
        assertEquals(0, nullClickX)

        // After a null reset, the next signaled bet starts fresh — needs a
        // partner before the streak can climb again.
        val firstAfterReset = service.recordAndScore(discordId, guildId, game, 100, 200, false)
        assertEquals(0, firstAfterReset, "first bet after reset re-seeds at 0")
    }

    @Test
    fun `state is partitioned per (user, guild)`() {
        val otherUser = 999L
        val otherGuild = 888L
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        // A different user, even at the same pixel, starts at 0.
        val otherUserStreak = service.recordAndScore(otherUser, guildId, game, 100, 200, false)
        // The same user in a different guild also starts at 0 — bot suspicion
        // does not leak across servers.
        val otherGuildStreak = service.recordAndScore(discordId, otherGuild, game, 100, 200, false)

        assertEquals(0, otherUserStreak)
        assertEquals(0, otherGuildStreak)
        // The original (user, guild) is unaffected and continues climbing.
        assertEquals(3, service.recordAndScore(discordId, guildId, game, 100, 200, false))
    }

    @Test
    fun `state is partitioned per gameKey so cross-game streaks don't leak`() {
        // A coinflip streak shouldn't influence the dice gate. Each game
        // tracks its own streak from the same (user, guild).
        service.recordAndScore(discordId, guildId, "coinflip", 100, 200, false)
        service.recordAndScore(discordId, guildId, "coinflip", 100, 200, false)
        service.recordAndScore(discordId, guildId, "coinflip", 100, 200, false)
        // Switching to dice at the same pixel is a fresh start.
        val firstDiceStreak = service.recordAndScore(discordId, guildId, "dice", 100, 200, false)
        assertEquals(0, firstDiceStreak)

        // The coinflip streak is still climbing.
        assertEquals(3, service.recordAndScore(discordId, guildId, "coinflip", 100, 200, false))
        // Dice climbs independently.
        assertEquals(1, service.recordAndScore(discordId, guildId, "dice", 100, 200, false))
        // Slots starts fresh too.
        assertEquals(0, service.recordAndScore(discordId, guildId, "slots", 100, 200, false))
    }

    // -------- Anti-autoclick event publishing --------

    @Test
    fun `publishes SessionOpened exactly once on the 0-to-1 transition`() {
        // First call seeds state at streak=0; second matching call transitions
        // to streak=1 — that's the moment we want a "session opened" event.
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.SessionOpened(guildId, discordId, game, streak = 1)
            )
        }
    }

    @Test
    fun `does not republish SessionOpened on subsequent streak increments`() {
        // Streak climbs 0 → 1 → 2 → 3. Only the first transition fires
        // SessionOpened; the rest are silent.
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)

        verify(exactly = 1) {
            eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>())
        }
        verify(exactly = 0) {
            eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>())
        }
    }

    @Test
    fun `publishes SessionClosed when streak resets via different pixel`() {
        // Build streak 0 → 1 → 2, then jump pixel beyond epsilon → reset to 0.
        // SessionClosed should fire on that close.
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 500, 600, false)

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.SessionClosed(guildId, discordId, game)
            )
        }
    }

    @Test
    fun `publishes SessionClosed when streak resets via mouseMoved=true`() {
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, mouseMoved = true)

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.SessionClosed(guildId, discordId, game)
            )
        }
    }

    @Test
    fun `publishes SessionClosed when streak resets via null signal`() {
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        service.recordAndScore(discordId, guildId, game, null, 200, false)

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.SessionClosed(guildId, discordId, game)
            )
        }
    }

    @Test
    fun `does not publish SessionClosed when streak was already 0`() {
        // No streak ever opened → null signal should not fire SessionClosed.
        // This is the Discord-bet-only path; no bogus close events.
        service.recordAndScore(discordId, guildId, game, null, null, null)
        service.recordAndScore(discordId, guildId, game, 100, 200, mouseMoved = true)
        service.recordAndScore(discordId, guildId, game, 500, 600, false)

        verify(exactly = 0) {
            eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>())
        }
    }

    @Test
    fun `publishes no events when streak stays at 0 across calls`() {
        // Single bet → streak 0 → no transitions, no events at all.
        service.recordAndScore(discordId, guildId, game, 100, 200, false)

        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent>()) }
    }

    @Test
    fun `re-opens a fresh session after a close-then-build cycle`() {
        // Open, close, then build back up — should fire one Open, one Close,
        // then a second Open as the streak ramps up again.
        service.recordAndScore(discordId, guildId, game, 100, 200, false) // 0
        service.recordAndScore(discordId, guildId, game, 100, 200, false) // 1 → Opened
        service.recordAndScore(discordId, guildId, game, 500, 600, false) // 0 → Closed
        service.recordAndScore(discordId, guildId, game, 500, 600, false) // 0
        service.recordAndScore(discordId, guildId, game, 500, 600, false) // 1 → Opened

        verify(exactly = 2) {
            eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>())
        }
        verify(exactly = 1) {
            eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>())
        }
    }

    @Test
    fun `events are scoped per gameKey - independent open and close per game`() {
        // Open dice session, then null-signal coinflip (which never opened) —
        // must not fire SessionClosed for coinflip, only the dice open event.
        service.recordAndScore(discordId, guildId, "dice", 100, 200, false)
        service.recordAndScore(discordId, guildId, "dice", 100, 200, false)        // dice Opened
        service.recordAndScore(discordId, guildId, "coinflip", null, null, null)   // no event

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.SessionOpened(guildId, discordId, "dice", streak = 1)
            )
        }
        verify(exactly = 0) {
            eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>())
        }
    }
}
