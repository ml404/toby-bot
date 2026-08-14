package bot.toby.scheduling

import database.service.user.SharedCubeService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SharedCubeRetentionJobTest {

    private val now: Instant = Instant.parse("2026-08-09T04:00:00Z")

    private lateinit var sharedCubes: SharedCubeService
    private lateinit var job: SharedCubeRetentionJob

    @BeforeEach
    fun setup() {
        sharedCubes = mockk(relaxed = true)
        job = SharedCubeRetentionJob(sharedCubes, Clock.fixed(now, ZoneOffset.UTC))
    }

    @Test
    fun `purges snapshots older than the retention window`() {
        val cutoff = slot<Instant>()
        every { sharedCubes.purge(capture(cutoff)) } returns 3

        job.purgeStaleSnapshots()

        assertEquals(now.minus(SharedCubeRetentionJob.RETENTION), cutoff.captured)
    }

    @Test
    fun `the window is long enough that a link outlives the draft it was made for`() {
        // A month-old link might still be pinned in someone's channel; the
        // point of the sweep is the ones nobody is coming back to.
        assertEquals(365, SharedCubeRetentionJob.RETENTION.toDays())
    }

    @Test
    fun `a purge that fails is logged, not thrown at the scheduler`() {
        every { sharedCubes.purge(any()) } throws RuntimeException("db down")

        job.purgeStaleSnapshots() // must not propagate

        verify(exactly = 1) { sharedCubes.purge(any()) }
    }

    @Test
    fun `a sweep that removes nothing is still a clean run`() {
        every { sharedCubes.purge(any()) } returns 0

        job.purgeStaleSnapshots()

        verify(exactly = 1) { sharedCubes.purge(any()) }
    }
}
