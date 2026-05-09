package database.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CasinoBotSuspicionServiceTest {

    private lateinit var service: CasinoBotSuspicionService

    private val discordId = 100L
    private val guildId = 200L
    private val game = "coinflip"

    @BeforeEach
    fun setup() {
        service = CasinoBotSuspicionService()
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
}
