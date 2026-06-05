package database.service.social.impl

import database.dto.social.ExcuseDto
import database.persistence.social.ExcusePersistence
import database.service.social.PagedExcuses
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit coverage for [DefaultExcuseService] — the caching/paging/approval
 * wrapper over [ExcusePersistence]. The Spring cache annotations are no-ops
 * when the bean is called directly (no proxy), so these tests exercise the
 * pure paging math, the approval state machine and the ownership guard with
 * a mocked persistence layer.
 */
class DefaultExcuseServiceTest {

    private lateinit var persistence: ExcusePersistence
    private lateinit var service: DefaultExcuseService

    private val guildId = 9L

    @BeforeEach
    fun setup() {
        persistence = mockk(relaxed = true)
        service = DefaultExcuseService()
        // The persistence collaborator is @Autowired into a private lateinit
        // field, so inject it reflectively for the unit test.
        DefaultExcuseService::class.java.getDeclaredField("excuseService").apply {
            isAccessible = true
            set(service, persistence)
        }
    }

    @Test
    fun `listApprovedPaged coerces the page and computes the offset`() {
        every { persistence.listApprovedPaged(guildId, 0, 10) } returns emptyList()
        every { persistence.countApproved(guildId) } returns 0L
        val firstPage = service.listApprovedPaged(guildId, page = 0, pageSize = 10) // coerced to 1
        assertEquals(1, firstPage.page)
        verify { persistence.listApprovedPaged(guildId, 0, 10) }

        every { persistence.listApprovedPaged(guildId, 20, 10) } returns emptyList()
        service.listApprovedPaged(guildId, page = 3, pageSize = 10)
        verify { persistence.listApprovedPaged(guildId, 20, 10) }
    }

    @Test
    fun `listPendingPaged computes the offset and totalCount`() {
        every { persistence.listPendingPaged(guildId, 5, 5) } returns emptyList()
        every { persistence.countPending(guildId) } returns 12L
        val result = service.listPendingPaged(guildId, page = 2, pageSize = 5)
        assertEquals(12L, result.totalCount)
        verify { persistence.listPendingPaged(guildId, 5, 5) }
    }

    @Test
    fun `searchApproved short-circuits on a blank query`() {
        val result = service.searchApproved(guildId, "   ", page = 1, pageSize = 10)
        assertEquals(0L, result.totalCount)
        assertTrue(result.rows.isEmpty())
        verify(exactly = 0) { persistence.searchApproved(any(), any(), any(), any()) }
    }

    @Test
    fun `searchApproved trims and delegates a real query`() {
        every { persistence.searchApproved(guildId, "sick", 0, 10) } returns emptyList()
        every { persistence.countSearchApproved(guildId, "sick") } returns 3L
        val result = service.searchApproved(guildId, "  sick  ", page = 1, pageSize = 10)
        assertEquals(3L, result.totalCount)
        verify { persistence.searchApproved(guildId, "sick", 0, 10) }
    }

    @Test
    fun `approveExcuse returns null for a missing excuse`() {
        every { persistence.getExcuseById(1L) } returns null
        assertNull(service.approveExcuse(1L))
        verify(exactly = 0) { persistence.updateExcuse(any()) }
    }

    @Test
    fun `approveExcuse is idempotent for an already-approved excuse`() {
        val dto = ExcuseDto(id = 1L, approved = true)
        every { persistence.getExcuseById(1L) } returns dto
        assertSame(dto, service.approveExcuse(1L))
        verify(exactly = 0) { persistence.updateExcuse(any()) }
    }

    @Test
    fun `approveExcuse flips a pending excuse and stamps the time`() {
        val dto = ExcuseDto(id = 1L, approved = false)
        every { persistence.getExcuseById(1L) } returns dto
        val saved = slot<ExcuseDto>()
        every { persistence.updateExcuse(capture(saved)) } answers { saved.captured }

        service.approveExcuse(1L)

        assertTrue(saved.captured.approved)
        assertTrue(saved.captured.approvedAt != null)
    }

    @Test
    fun `canRequesterDeleteOwnPending enforces ownership and pending state`() {
        every { persistence.getExcuseById(1L) } returns null
        assertFalse(service.canRequesterDeleteOwnPending(1L, requesterDiscordId = 5L))

        every { persistence.getExcuseById(2L) } returns ExcuseDto(id = 2L, approved = true, authorDiscordId = 5L)
        assertFalse(service.canRequesterDeleteOwnPending(2L, requesterDiscordId = 5L))

        every { persistence.getExcuseById(3L) } returns ExcuseDto(id = 3L, approved = false, authorDiscordId = 99L)
        assertFalse(service.canRequesterDeleteOwnPending(3L, requesterDiscordId = 5L))

        every { persistence.getExcuseById(4L) } returns ExcuseDto(id = 4L, approved = false, authorDiscordId = 5L)
        assertTrue(service.canRequesterDeleteOwnPending(4L, requesterDiscordId = 5L))
    }

    @Test
    fun `count and delete operations delegate to persistence`() {
        every { persistence.countApproved(guildId) } returns 7L
        every { persistence.countPending(guildId) } returns 2L
        assertEquals(7L, service.countApproved(guildId))
        assertEquals(2L, service.countPending(guildId))

        service.deleteExcuseByGuildId(guildId)
        service.deleteExcuseById(10L)
        verify { persistence.deleteAllExcusesForGuild(guildId) }
        verify { persistence.deleteExcuseById(10L) }
    }
}

/**
 * Bonus: the [PagedExcuses] computed pagination helpers, which the web UI
 * relies on for prev/next controls.
 */
class PagedExcusesTest {

    @Test
    fun `totalPages is one when empty and rounds up otherwise`() {
        assertEquals(1, PagedExcuses(emptyList(), page = 1, pageSize = 10, totalCount = 0L).totalPages)
        assertEquals(3, PagedExcuses(emptyList(), page = 1, pageSize = 10, totalCount = 21L).totalPages)
        assertEquals(2, PagedExcuses(emptyList(), page = 1, pageSize = 10, totalCount = 20L).totalPages)
    }

    @Test
    fun `hasPrev and hasNext reflect the current page position`() {
        val middle = PagedExcuses(emptyList(), page = 2, pageSize = 10, totalCount = 30L)
        assertTrue(middle.hasPrev)
        assertTrue(middle.hasNext)

        val first = PagedExcuses(emptyList(), page = 1, pageSize = 10, totalCount = 30L)
        assertFalse(first.hasPrev)
        assertTrue(first.hasNext)

        val last = PagedExcuses(emptyList(), page = 3, pageSize = 10, totalCount = 30L)
        assertTrue(last.hasPrev)
        assertFalse(last.hasNext)
    }
}
