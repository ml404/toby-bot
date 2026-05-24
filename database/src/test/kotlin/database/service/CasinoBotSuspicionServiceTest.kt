package database.service

import common.events.moderation.AntiAutoclickEvent
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import database.service.casino.CasinoBotSuspicionService

class CasinoBotSuspicionServiceTest {

    private lateinit var service: CasinoBotSuspicionService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var nowRef: AtomicReference<Instant>
    private lateinit var fakeClock: Clock

    private val discordId = 100L
    private val guildId = 200L
    private val game = "coinflip"

    @BeforeEach
    fun setup() {
        eventPublisher = mockk(relaxed = true)
        nowRef = AtomicReference(Instant.parse("2026-05-09T12:00:00Z"))
        // Mutable fixed clock — tests advance `nowRef` to simulate gaps.
        fakeClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = nowRef.get()
        }
        service = CasinoBotSuspicionService(eventPublisher, fakeClock)
    }

    private fun bot(times: Int, x: Int = 100, y: Int = 200): Int {
        var last = 0
        repeat(times) {
            last = service.recordAndScore(discordId, guildId, game, x, y, mouseMoved = false)
        }
        return last
    }

    private fun advanceSeconds(seconds: Long) {
        nowRef.set(nowRef.get().plusSeconds(seconds))
    }

    @Test
    fun `first ever bet returns 0 - no prior signal to match against`() {
        val score = service.recordAndScore(discordId, guildId, game, 100, 200, mouseMoved = false)
        assertEquals(0, score)
    }

    @Test
    fun `natural slots-style session of 75 same-spot clicks does not flag`() {
        // The user-reported false positive: a minute of identical-pixel,
        // no-mouse-motion play (~75 spins) must NOT open the gate.
        repeat(75) {
            val score = service.recordAndScore(discordId, guildId, game, 100, 200, mouseMoved = false)
            assertEquals(0, score, "score must stay 0 below MIN_SAMPLE")
        }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>()) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>()) }
    }

    @Test
    fun `1 minute + 10s pause + 1 minute does not flag - pause clears window if longer than IDLE_RESET`() {
        // Owner's stated scenario: "1 min play, 10s pause, 1 min play"
        // is plausible natural behaviour and must not flag. Two minutes
        // of total play with a 16 s pause between them clears the
        // window during the pause (gap > IDLE_RESET_MS = 15 s) so
        // each minute's 75 bets accumulates from zero — neither half
        // approaches MIN_SAMPLE.
        bot(75)
        advanceSeconds(16) // > IDLE_RESET_MS
        bot(75)

        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>()) }
    }

    @Test
    fun `four minutes of uninterrupted bot-pattern play opens the gate`() {
        // Continuous 300 bets with no gap > IDLE_RESET — that's
        // explicitly the "this is a bot" threshold the algorithm flags.
        bot(300)

        verify(exactly = 1) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>()) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>()) }
    }

    @Test
    fun `score is the match count once gate is open and ramps with continued bot signal`() {
        // After the gate opens, recordAndScore returns the matches count
        // so CasinoEdgeService's `streak * 2.5pp` formula reads "fully
        // saturated" and applies the per-game cap.
        val score = bot(310)

        assertTrue(score >= 280, "expected match count near saturation; got $score")
    }

    @Test
    fun `mouse-moved click partway through dilutes the ratio but does not close immediately`() {
        bot(300)
        // Inject one motion click while still inside the same window.
        service.recordAndScore(discordId, guildId, game, 100, 200, mouseMoved = true)

        verify(exactly = 1) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>()) }
        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>()) }
    }

    @Test
    fun `sustained mouse motion eventually closes an open session`() {
        // Open the gate, then deliver a long burst of motion clicks until
        // the ratio drops under RATIO_CLOSE (0.60).
        bot(300)
        repeat(200) {
            service.recordAndScore(discordId, guildId, game, 100, 200, mouseMoved = true)
        }

        verify(exactly = 1) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>()) }
    }

    @Test
    fun `idle gap longer than IDLE_RESET_MS clears the window pre-flag`() {
        // Build a window of 299 bot clicks (one short of MIN_SAMPLE).
        bot(299)

        // Walk away long enough to cross IDLE_RESET.
        advanceSeconds(20)

        // 200 more bot-shaped bets afterwards — fresh window, never
        // approaches MIN_SAMPLE.
        bot(200)

        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>()) }
    }

    @Test
    fun `idle gap closes an already-open session`() {
        bot(300)
        advanceSeconds(20)
        // The next bet observes the gap and closes the session.
        service.recordAndScore(discordId, guildId, game, 100, 200, mouseMoved = false)

        verify(exactly = 1) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>()) }
    }

    @Test
    fun `pixel jitter inside epsilon still counts as same spot`() {
        // Comparison is to the IMMEDIATELY prior click (not the first
        // click), so a sustained autoclicker that jitters by ±EPSILON_PX
        // every time still climbs the window.
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        repeat(299) { i ->
            val dx = if (i % 2 == 0) 1 else -1
            val dy = if (i % 3 == 0) 1 else 0
            service.recordAndScore(discordId, guildId, game, 100 + dx, 200 + dy, mouseMoved = false)
        }
        verify(exactly = 1) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>()) }
    }

    @Test
    fun `single drift bet contributes a non-match but does not block opening`() {
        // 5-pixel jump in one bet doesn't match — it's `false` in the
        // window. One non-match in 300 doesn't break threshold (ratio
        // still ≥ 0.95) — gate still opens.
        service.recordAndScore(discordId, guildId, game, 100, 200, false)
        repeat(149) { service.recordAndScore(discordId, guildId, game, 100, 200, false) }
        // 1 drift bet (5 px jump from prior at 100)
        service.recordAndScore(discordId, guildId, game, 105, 200, false)
        repeat(149) { service.recordAndScore(discordId, guildId, game, 105, 200, false) }

        verify(exactly = 1) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>()) }
    }

    @Test
    fun `null signal closes an open session and returns 0`() {
        bot(300)

        // Discord-path bet (null signals) → close the session, return 0.
        val score = service.recordAndScore(discordId, guildId, game, null, null, null)
        assertEquals(0, score)

        verify(exactly = 1) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>()) }
    }

    @Test
    fun `null signal with no open session does not publish a close event`() {
        // Discord-only player who never tripped the gate shouldn't see a
        // bogus SessionClosed when their bet hits the service.
        service.recordAndScore(discordId, guildId, game, null, null, null)
        service.recordAndScore(discordId, guildId, game, 100, 200, mouseMoved = true)
        service.recordAndScore(discordId, guildId, game, 500, 600, mouseMoved = false)

        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent>()) }
    }

    @Test
    fun `state is partitioned per (user, guild)`() {
        val otherUser = 999L
        val otherGuild = 888L
        bot(300)

        // A different user, even at the same pixel, is a fresh window.
        repeat(75) {
            service.recordAndScore(otherUser, guildId, game, 100, 200, false)
        }
        // Same user different guild — also a fresh window.
        repeat(75) {
            service.recordAndScore(discordId, otherGuild, game, 100, 200, false)
        }

        // Total opens: exactly one (the original user/guild). The match
        // pinpoints the triple so any cross-bleed would surface as
        // additional events.
        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<AntiAutoclickEvent.SessionOpened> {
                    it.guildId == guildId && it.discordId == discordId && it.gameKey == game
                }
            )
        }
        verify(exactly = 0) {
            eventPublisher.publishEvent(
                match<AntiAutoclickEvent.SessionOpened> {
                    it.discordId == otherUser || it.guildId == otherGuild
                }
            )
        }
    }

    @Test
    fun `state is partitioned per gameKey`() {
        // A coinflip bot session shouldn't influence the dice gate.
        repeat(300) {
            service.recordAndScore(discordId, guildId, "coinflip", 100, 200, false)
        }
        repeat(75) {
            service.recordAndScore(discordId, guildId, "dice", 100, 200, false)
        }

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                match<AntiAutoclickEvent.SessionOpened> { it.gameKey == "coinflip" }
            )
        }
        verify(exactly = 0) {
            eventPublisher.publishEvent(
                match<AntiAutoclickEvent.SessionOpened> { it.gameKey == "dice" }
            )
        }
    }

    @Test
    fun `re-opens a fresh session after a close-then-build cycle`() {
        bot(300)                                                // Opened
        repeat(200) {
            service.recordAndScore(discordId, guildId, game, 100, 200, mouseMoved = true)
        }                                                        // Closed
        // Build window back up with bot clicks. Need enough bot bets
        // post-close to push ratio above 0.95 again.
        repeat(500) { service.recordAndScore(discordId, guildId, game, 100, 200, false) }

        verify(exactly = 2) {
            eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>())
        }
        verify(exactly = 1) {
            eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>())
        }
    }

    @Test
    fun `idle clear during open session closes the session even if next bet matches`() {
        bot(300)
        advanceSeconds(20)
        // First bet after the gap can't match (window was cleared, no
        // prior pixel to compare). Even with the same pixel + no motion,
        // size = 1 → cannot reopen.
        val score = service.recordAndScore(discordId, guildId, game, 100, 200, false)

        assertEquals(0, score)
        verify(exactly = 1) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionClosed>()) }
        // No second SessionOpened — we're rebuilding from zero.
        verify(exactly = 1) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.SessionOpened>()) }
    }
}
