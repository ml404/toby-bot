package bot.toby.activity

import database.service.ActivityMonthlyRollupService
import database.service.ActivitySessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ActivityRetentionJobTest {

    private lateinit var activitySessionService: ActivitySessionService
    private lateinit var activityMonthlyRollupService: ActivityMonthlyRollupService
    private lateinit var job: ActivityRetentionJob

    @BeforeEach
    fun setup() {
        activitySessionService = mockk(relaxed = true)
        activityMonthlyRollupService = mockk(relaxed = true)
        job = ActivityRetentionJob(activitySessionService, activityMonthlyRollupService)
    }

    @Test
    fun `purgeStaleRows purges sessions older than the 30-day cutoff`() {
        val cutoff = slot<Instant>()
        every { activitySessionService.deleteClosedBefore(capture(cutoff)) } returns 0

        job.purgeStaleRows()

        val expected = Instant.now().minusSeconds(30L * 24L * 3600L)
        val actual = cutoff.captured
        val diff = java.time.Duration.between(actual, expected).abs().seconds
        assertTrue(diff < 10) { "Expected cutoff within 10s of 30 days ago but got diff=$diff" }
    }

    @Test
    fun `purgeStaleRows purges rollups older than 12 months`() {
        val cutoff = slot<LocalDate>()
        every { activityMonthlyRollupService.deleteBefore(capture(cutoff)) } returns 0

        job.purgeStaleRows()

        val expected = LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1).minusMonths(12)
        assert(cutoff.captured == expected) { "Expected cutoff $expected, got ${cutoff.captured}" }
    }

    @Test
    fun `purgeStaleRows continues when session purge fails`() {
        every { activitySessionService.deleteClosedBefore(any()) } throws RuntimeException("boom")

        job.purgeStaleRows()

        verify(exactly = 1) { activityMonthlyRollupService.deleteBefore(any()) }
    }
}
