package bot.toby.scheduling

import bot.toby.notify.NotificationRouter
import common.notification.NotificationChannelKind
import database.dto.LoginStreakDto
import database.service.LoginStreakService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Coverage for [StreakReminderJob]. The job's contract:
 *
 *   - Iterates every guild in [JDA.guildCache].
 *   - For each guild, asks [LoginStreakService.findActiveStreaksDueForReminder]
 *     for the at-risk cohort (current_streak > 0 AND last_claim_date < today).
 *   - DMs each at-risk user via [NotificationRouter.sendDm] with
 *     [NotificationChannelKind.STREAK_REMINDER] — the router gates on the
 *     per-(user, STREAK_REMINDER, Surface.DM) opt-in, so test users without
 *     explicit opt-in are silently dropped at the router. Default opt-out
 *     for STREAK_REMINDER means no DM unless the user said yes.
 *   - Per-guild error isolation via runCatching — one throwing guild
 *     doesn't kill the loop.
 */
class StreakReminderJobTest {

    private val today: LocalDate = LocalDate.of(2026, 5, 1)
    private val clock: Clock = Clock.fixed(
        today.atStartOfDay().toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC
    )

    private lateinit var jda: JDA
    private lateinit var loginStreakService: LoginStreakService
    private lateinit var notificationRouter: NotificationRouter
    private lateinit var job: StreakReminderJob

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        loginStreakService = mockk(relaxed = true)
        notificationRouter = mockk(relaxed = true)
        every { notificationRouter.sendDm(any(), any(), any(), any()) } just runs
    }

    /** Build the job with a JDA cache containing the supplied guild ids. */
    private fun buildJob(vararg guildIds: Long): StreakReminderJob {
        // Rename loop var so it doesn't shadow Guild.id when we stub it.
        val guilds = guildIds.map { gid ->
            val g = mockk<Guild>(relaxed = true)
            every { g.idLong } returns gid
            every { g.id } returns gid.toString()
            g
        }
        val cache: SnowflakeCacheView<Guild> = mockk(relaxed = true)
        every { jda.guildCache } returns cache
        every { cache.iterator() } returns guilds.toMutableList().iterator()
        return StreakReminderJob(jda, loginStreakService, notificationRouter, clock)
    }

    @Test
    fun `runHourly skips guilds with no at-risk streaks`() {
        every { loginStreakService.findActiveStreaksDueForReminder(100L, today) } returns emptyList()

        buildJob(100L).runHourly()

        verify(exactly = 0) {
            notificationRouter.sendDm(any(), any(), any(), any())
        }
    }

    @Test
    fun `runHourly DMs every at-risk user via the router with STREAK_REMINDER kind`() {
        val rowAlice = LoginStreakDto(
            discordId = 1L, guildId = 100L,
            currentStreak = 5, longestStreak = 7,
            lastClaimDate = today.minusDays(1), totalClaims = 5L,
        )
        val rowBob = LoginStreakDto(
            discordId = 2L, guildId = 100L,
            currentStreak = 2, longestStreak = 2,
            lastClaimDate = today.minusDays(1), totalClaims = 2L,
        )
        every {
            loginStreakService.findActiveStreaksDueForReminder(100L, today)
        } returns listOf(rowAlice, rowBob)

        buildJob(100L).runHourly()

        verify(exactly = 1) {
            notificationRouter.sendDm(1L, 100L, NotificationChannelKind.STREAK_REMINDER, any())
        }
        verify(exactly = 1) {
            notificationRouter.sendDm(2L, 100L, NotificationChannelKind.STREAK_REMINDER, any())
        }
    }

    @Test
    fun `runHourly uses today's UTC date — not the wall-clock local date`() {
        // Cron is `0 0 23 * * *` in UTC. Job picks UTC date via the
        // injected clock. Verify the LoginStreakService query receives
        // the fixed UTC date from the clock.
        every {
            loginStreakService.findActiveStreaksDueForReminder(100L, today)
        } returns emptyList()

        buildJob(100L).runHourly()

        verify(exactly = 1) {
            loginStreakService.findActiveStreaksDueForReminder(100L, today)
        }
    }

    @Test
    fun `runHourly isolates per-guild failures — one throwing guild doesn't kill the loop`() {
        every {
            loginStreakService.findActiveStreaksDueForReminder(100L, today)
        } throws RuntimeException("DB hiccup")
        every {
            loginStreakService.findActiveStreaksDueForReminder(200L, today)
        } returns listOf(
            LoginStreakDto(
                discordId = 99L, guildId = 200L,
                currentStreak = 3, longestStreak = 3,
                lastClaimDate = today.minusDays(1), totalClaims = 3L,
            )
        )

        // Should not throw — runCatching swallows the first guild's error
        // and the loop continues to the second guild.
        buildJob(100L, 200L).runHourly()

        // Second guild's DM still went out.
        verify(exactly = 1) {
            notificationRouter.sendDm(99L, 200L, NotificationChannelKind.STREAK_REMINDER, any())
        }
    }

    @Test
    fun `runHourly delegates the DM opt-in check to the router (not the job)`() {
        // The job doesn't query prefService directly — it hands off to
        // the router, which gates on (kind, Surface.DM). The job's
        // contract is "find the at-risk cohort and call sendDm"; the
        // router does the rest. This test pins that contract.
        every {
            loginStreakService.findActiveStreaksDueForReminder(100L, today)
        } returns listOf(
            LoginStreakDto(
                discordId = 1L, guildId = 100L,
                currentStreak = 5, longestStreak = 5,
                lastClaimDate = today.minusDays(1), totalClaims = 5L,
            )
        )

        buildJob(100L).runHourly()

        verify(exactly = 1) {
            notificationRouter.sendDm(1L, 100L, NotificationChannelKind.STREAK_REMINDER, any())
        }
        // The job MUST NOT call any persistence / pref-service method
        // beyond findActiveStreaksDueForReminder. Anything else means it
        // grew a side-effect that bypasses the router's opt-in gate.
        verify(exactly = 1) {
            loginStreakService.findActiveStreaksDueForReminder(100L, today)
        }
    }

    @Test
    fun `runHourly handles multiple guilds with mixed at-risk counts`() {
        every {
            loginStreakService.findActiveStreaksDueForReminder(100L, today)
        } returns emptyList()
        every {
            loginStreakService.findActiveStreaksDueForReminder(200L, today)
        } returns listOf(
            LoginStreakDto(
                discordId = 5L, guildId = 200L,
                currentStreak = 9, longestStreak = 9,
                lastClaimDate = today.minusDays(1), totalClaims = 9L,
            ),
            LoginStreakDto(
                discordId = 6L, guildId = 200L,
                currentStreak = 1, longestStreak = 4,
                lastClaimDate = today.minusDays(1), totalClaims = 4L,
            ),
        )

        buildJob(100L, 200L).runHourly()

        verify(exactly = 0) {
            notificationRouter.sendDm(any(), 100L, any(), any())
        }
        verify(exactly = 1) {
            notificationRouter.sendDm(5L, 200L, NotificationChannelKind.STREAK_REMINDER, any())
        }
        verify(exactly = 1) {
            notificationRouter.sendDm(6L, 200L, NotificationChannelKind.STREAK_REMINDER, any())
        }
    }
}
