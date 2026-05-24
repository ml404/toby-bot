package web.service

import database.dto.social.ExcuseDto
import database.dto.user.UserDto
import database.service.social.ExcuseService
import database.service.social.PagedExcuses
import database.service.user.UserService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExcuseWebServiceTest {

    private lateinit var excuseService: ExcuseService
    private lateinit var userService: UserService
    private lateinit var jda: JDA
    private lateinit var introWebService: IntroWebService
    private lateinit var service: ExcuseWebService

    private val guildId = 999L
    private val authorDiscordId = 111L
    private val otherDiscordId = 222L

    @BeforeEach
    fun setup() {
        excuseService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        service = ExcuseWebService(excuseService, userService, jda, introWebService)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    // getPage — tab gating

    @Test
    fun `getPage downgrades pending tab to approved for non-superusers`() {
        every { userService.getUserById(otherDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns false
        }
        every { excuseService.listApprovedPaged(guildId, 1, ExcuseWebService.PAGE_SIZE) } returns
            PagedExcuses(emptyList(), 1, ExcuseWebService.PAGE_SIZE, 0L)

        val result = service.getPage(guildId, "pending", null, 1, otherDiscordId)

        assertEquals("approved", result.requestedTab)
        assertFalse(result.isSuperUser)
        verify(exactly = 0) { excuseService.listPendingPaged(any(), any(), any()) }
        verify { excuseService.listApprovedPaged(guildId, 1, ExcuseWebService.PAGE_SIZE) }
    }

    @Test
    fun `getPage routes pending tab to listPendingPaged when superuser`() {
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns true
        }
        every { excuseService.listPendingPaged(guildId, 1, ExcuseWebService.PAGE_SIZE) } returns
            PagedExcuses(emptyList(), 1, ExcuseWebService.PAGE_SIZE, 0L)

        val result = service.getPage(guildId, "pending", null, 1, authorDiscordId)

        assertEquals("pending", result.requestedTab)
        assertTrue(result.isSuperUser)
        verify { excuseService.listPendingPaged(guildId, 1, ExcuseWebService.PAGE_SIZE) }
    }

    @Test
    fun `getPage with non-blank query routes to searchApproved on approved tab`() {
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns false
        }
        every {
            excuseService.searchApproved(guildId, "rain", 1, ExcuseWebService.PAGE_SIZE)
        } returns PagedExcuses(emptyList(), 1, ExcuseWebService.PAGE_SIZE, 0L)

        service.getPage(guildId, "approved", "rain", 1, authorDiscordId)

        verify { excuseService.searchApproved(guildId, "rain", 1, ExcuseWebService.PAGE_SIZE) }
    }

    @Test
    fun `getPage maps row view models with canDelete for own pending`() {
        val dto = ExcuseDto(
            id = 7L,
            guildId = guildId,
            author = "Author",
            excuse = "I forgot",
            approved = false,
            authorDiscordId = authorDiscordId,
        )
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns true
        }
        every {
            excuseService.listPendingPaged(guildId, 1, ExcuseWebService.PAGE_SIZE)
        } returns PagedExcuses(listOf(dto), 1, ExcuseWebService.PAGE_SIZE, 1L)

        val result = service.getPage(guildId, "pending", null, 1, authorDiscordId)

        assertEquals(1, result.rows.size)
        val row = result.rows.first()
        assertTrue(row.isAuthor)
        assertTrue(row.canDelete) // superuser
        assertTrue(row.canApprove)
    }

    @Test
    fun `getPage marks canDelete=true for own pending even when not superuser`() {
        val dto = ExcuseDto(
            id = 7L,
            guildId = guildId,
            author = "Author",
            excuse = "I forgot",
            approved = false,
            authorDiscordId = authorDiscordId,
        )
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns false
        }
        // Non-superuser viewing pending downgrades to approved tab; reset with approved
        // search since that is what gets called instead. Use approved tab + no query.
        every {
            excuseService.listApprovedPaged(guildId, 1, ExcuseWebService.PAGE_SIZE)
        } returns PagedExcuses(listOf(dto), 1, ExcuseWebService.PAGE_SIZE, 1L)

        val result = service.getPage(guildId, "approved", null, 1, authorDiscordId)

        val row = result.rows.first()
        assertTrue(row.isAuthor)
        // Pending row owned by us is deletable
        assertTrue(row.canDelete)
        assertFalse(row.canApprove) // only superusers can approve
    }

    // submit

    @Test
    fun `submit rejects blank text`() {
        val result = service.submit(guildId, "   ", null, authorDiscordId)
        assertFalse(result.ok)
        assertEquals("Provide some excuse text.", result.error)
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
    }

    @Test
    fun `submit rejects duplicates case-insensitively`() {
        every { userService.getUserById(authorDiscordId, guildId) } returns null
        every { excuseService.listAllGuildExcuses(guildId) } returns listOf(
            ExcuseDto(id = 1L, guildId = guildId, author = "X", excuse = "RAIN DELAY", approved = true)
        )

        val result = service.submit(guildId, "rain delay", null, authorDiscordId)

        assertFalse(result.ok)
        assertEquals("That excuse already exists for this server.", result.error)
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
    }

    @Test
    fun `submit ignores author override from non-superusers`() {
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns false
        }
        every { excuseService.listAllGuildExcuses(guildId) } returns emptyList()
        every { jda.getGuildById(guildId) } returns mockk(relaxed = true)
        every { excuseService.createNewExcuse(any()) } returns
            ExcuseDto(id = 1L, guildId = guildId, excuse = "rain", authorDiscordId = authorDiscordId)

        val result = service.submit(guildId, "rain", otherDiscordId, authorDiscordId)

        assertTrue(result.ok)
        verify {
            excuseService.createNewExcuse(withArg<ExcuseDto> {
                assertEquals(authorDiscordId, it.authorDiscordId)
            })
        }
    }

    @Test
    fun `submit honours author override from superusers`() {
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns true
        }
        every { excuseService.listAllGuildExcuses(guildId) } returns emptyList()
        every { jda.getGuildById(guildId) } returns mockk(relaxed = true)
        every { excuseService.createNewExcuse(any()) } returns
            ExcuseDto(id = 1L, guildId = guildId, excuse = "rain", authorDiscordId = otherDiscordId)

        val result = service.submit(guildId, "rain", otherDiscordId, authorDiscordId)

        assertTrue(result.ok)
        verify {
            excuseService.createNewExcuse(withArg<ExcuseDto> {
                assertEquals(otherDiscordId, it.authorDiscordId)
            })
        }
    }

    // approve

    @Test
    fun `approve fails for non-superusers`() {
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns false
        }

        val error = service.approve(1L, authorDiscordId, guildId)

        assertEquals("You don't have permission to approve excuses.", error)
        verify(exactly = 0) { excuseService.approveExcuse(any()) }
    }

    @Test
    fun `approve refuses to cross guild boundaries`() {
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns true
        }
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = 555L, approved = false)

        val error = service.approve(1L, authorDiscordId, guildId)

        assertEquals("Excuse not found.", error)
        verify(exactly = 0) { excuseService.approveExcuse(any()) }
    }

    @Test
    fun `approve succeeds for superuser on matching guild`() {
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns true
        }
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = guildId, approved = false)
        every { excuseService.approveExcuse(1L) } returns
            ExcuseDto(id = 1L, guildId = guildId, approved = true)

        val error = service.approve(1L, authorDiscordId, guildId)

        assertNull(error)
        verify { excuseService.approveExcuse(1L) }
    }

    // delete

    @Test
    fun `delete allows author of own pending excuse`() {
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns false
        }
        every { excuseService.getExcuseById(1L) } returns ExcuseDto(
            id = 1L,
            guildId = guildId,
            approved = false,
            authorDiscordId = authorDiscordId,
        )
        every { excuseService.deleteExcuseById(1L) } just Runs

        val error = service.delete(1L, authorDiscordId, guildId)

        assertNull(error)
        verify { excuseService.deleteExcuseById(1L) }
    }

    @Test
    fun `delete denies non-author of someone elses pending excuse`() {
        every { userService.getUserById(otherDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns false
        }
        every { excuseService.getExcuseById(1L) } returns ExcuseDto(
            id = 1L,
            guildId = guildId,
            approved = false,
            authorDiscordId = authorDiscordId,
        )

        val error = service.delete(1L, otherDiscordId, guildId)

        assertEquals("You don't have permission to delete that excuse.", error)
        verify(exactly = 0) { excuseService.deleteExcuseById(any()) }
    }

    @Test
    fun `delete denies author of approved excuse unless superuser`() {
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns false
        }
        every { excuseService.getExcuseById(1L) } returns ExcuseDto(
            id = 1L,
            guildId = guildId,
            approved = true,
            authorDiscordId = authorDiscordId,
        )

        val error = service.delete(1L, authorDiscordId, guildId)

        assertEquals("You don't have permission to delete that excuse.", error)
    }

    @Test
    fun `delete allows superuser regardless of authorship`() {
        every { userService.getUserById(otherDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns true
        }
        every { excuseService.getExcuseById(1L) } returns ExcuseDto(
            id = 1L,
            guildId = guildId,
            approved = true,
            authorDiscordId = authorDiscordId,
        )
        every { excuseService.deleteExcuseById(1L) } just Runs

        val error = service.delete(1L, otherDiscordId, guildId)

        assertNull(error)
        verify { excuseService.deleteExcuseById(1L) }
    }

    // random

    @Test
    fun `getRandomApproved returns a row from approved list`() {
        val rows = listOf(
            ExcuseDto(id = 7L, guildId = guildId, author = "A", excuse = "x", approved = true)
        )
        every { excuseService.listApprovedGuildExcuses(guildId) } returns rows

        val pick = service.getRandomApproved(guildId)

        assertNotNull(pick)
        assertEquals(7L, pick!!.id)
        assertEquals("x", pick.text)
    }

    @Test
    fun `getRandomApproved returns null when nothing is approved`() {
        every { excuseService.listApprovedGuildExcuses(guildId) } returns emptyList()

        assertNull(service.getRandomApproved(guildId))
    }

    // resolveDisplayAuthor (exercised through getRandomApproved + getPage)

    @Test
    fun `getRandomApproved resolves author to current member effective name`() {
        val row = ExcuseDto(
            id = 9L,
            guildId = guildId,
            author = "OldSnapshot",
            excuse = "the dog ate it",
            approved = true,
            authorDiscordId = authorDiscordId,
        )
        val guild = mockk<net.dv8tion.jda.api.entities.Guild>()
        val member = mockk<net.dv8tion.jda.api.entities.Member>()
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(authorDiscordId) } returns member
        every { member.effectiveName } returns "CurrentNick"
        every { excuseService.listApprovedGuildExcuses(guildId) } returns listOf(row)

        val pick = service.getRandomApproved(guildId)

        assertNotNull(pick)
        assertEquals("CurrentNick", pick!!.author)
    }

    @Test
    fun `getRandomApproved falls back to JDA user name when member has left`() {
        val row = ExcuseDto(
            id = 9L,
            guildId = guildId,
            author = "OldSnapshot",
            excuse = "x",
            approved = true,
            authorDiscordId = authorDiscordId,
        )
        val guild = mockk<net.dv8tion.jda.api.entities.Guild>()
        val user = mockk<net.dv8tion.jda.api.entities.User>()
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(authorDiscordId) } returns null
        every { jda.getUserById(authorDiscordId) } returns user
        every { user.name } returns "GlobalName"
        every { excuseService.listApprovedGuildExcuses(guildId) } returns listOf(row)

        val pick = service.getRandomApproved(guildId)

        assertEquals("GlobalName", pick!!.author)
    }

    @Test
    fun `getRandomApproved falls back to snapshot when JDA lookups all miss`() {
        val row = ExcuseDto(
            id = 9L,
            guildId = guildId,
            author = "Legacy",
            excuse = "x",
            approved = true,
            authorDiscordId = authorDiscordId,
        )
        every { jda.getGuildById(guildId) } returns null
        every { jda.getUserById(authorDiscordId) } returns null
        every { excuseService.listApprovedGuildExcuses(guildId) } returns listOf(row)

        val pick = service.getRandomApproved(guildId)

        assertEquals("Legacy", pick!!.author)
    }

    @Test
    fun `getRandomApproved uses snapshot for legacy rows without authorDiscordId`() {
        val row = ExcuseDto(
            id = 9L,
            guildId = guildId,
            author = "Legacy",
            excuse = "x",
            approved = true,
            authorDiscordId = null,
        )
        every { excuseService.listApprovedGuildExcuses(guildId) } returns listOf(row)

        val pick = service.getRandomApproved(guildId)

        assertEquals("Legacy", pick!!.author)
    }

    @Test
    fun `getPage row view model uses current member name when authorDiscordId resolves`() {
        val row = ExcuseDto(
            id = 9L,
            guildId = guildId,
            author = "OldSnapshot",
            excuse = "x",
            approved = true,
            authorDiscordId = authorDiscordId,
        )
        every { userService.getUserById(authorDiscordId, guildId) } returns mockk<UserDto> {
            every { superUser } returns false
        }
        every {
            excuseService.listApprovedPaged(guildId, 1, ExcuseWebService.PAGE_SIZE)
        } returns PagedExcuses(listOf(row), 1, ExcuseWebService.PAGE_SIZE, 1L)
        val guild = mockk<net.dv8tion.jda.api.entities.Guild>()
        val member = mockk<net.dv8tion.jda.api.entities.Member>()
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(authorDiscordId) } returns member
        every { member.effectiveName } returns "CurrentNick"

        val result = service.getPage(guildId, "approved", null, 1, authorDiscordId)

        assertEquals("CurrentNick", result.rows.first().author)
    }
}
