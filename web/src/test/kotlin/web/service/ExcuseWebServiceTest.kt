package web.service

import database.dto.social.ExcuseDto
import database.service.social.ExcuseService
import database.service.social.PagedExcuses
import database.service.user.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import database.dto.user.UserDto

class ExcuseWebServiceTest {

    private lateinit var excuseService: ExcuseService
    private lateinit var userService: UserService
    private lateinit var jda: JDA
    private lateinit var introWebService: IntroWebService
    private lateinit var service: ExcuseWebService

    private val guildId = 100L
    private val discordId = 200L

    @BeforeEach
    fun setup() {
        excuseService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        service = ExcuseWebService(excuseService, userService, jda, introWebService)
    }

    // ---- getMutualGuilds ----

    @Test
    fun `getMutualGuilds delegates to introWebService`() {
        val guilds = listOf(GuildInfo("1", "Test", null))
        every { introWebService.getMutualGuilds("token") } returns guilds
        assertEquals(guilds, service.getMutualGuilds("token"))
    }

    // ---- getGuildName ----

    @Test
    fun `getGuildName returns guild name when bot is in guild`() {
        val guild = mockk<Guild>(relaxed = true)
        every { guild.name } returns "My Server"
        every { jda.getGuildById(guildId) } returns guild
        assertEquals("My Server", service.getGuildName(guildId))
    }

    @Test
    fun `getGuildName returns null when bot is not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertNull(service.getGuildName(guildId))
    }

    // ---- isSuperUser ----

    @Test
    fun `isSuperUser returns true when user has superUser flag`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = true }
        assertTrue(service.isSuperUser(discordId, guildId))
    }

    @Test
    fun `isSuperUser returns false when user has no superUser flag`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        assertFalse(service.isSuperUser(discordId, guildId))
    }

    @Test
    fun `isSuperUser returns false when user does not exist`() {
        every { userService.getUserById(discordId, guildId) } returns null
        assertFalse(service.isSuperUser(discordId, guildId))
    }

    // ---- getGuildMembers ----

    @Test
    fun `getGuildMembers returns empty list when bot not in guild`() {
        every { jda.getGuildById(guildId) } returns null
        assertTrue(service.getGuildMembers(guildId).isEmpty())
    }

    @Test
    fun `getGuildMembers filters bots and sorts by name`() {
        val guild = mockk<Guild>(relaxed = true)
        val humanUser = mockk<User>(relaxed = true) { every { isBot } returns false }
        val botUser = mockk<User>(relaxed = true) { every { isBot } returns true }

        val human1 = mockk<Member>(relaxed = true) {
            every { user } returns humanUser
            every { id } returns "1"
            every { effectiveName } returns "Zara"
            every { effectiveAvatarUrl } returns "https://example.com/a.png"
        }
        val human2 = mockk<Member>(relaxed = true) {
            every { user } returns humanUser
            every { id } returns "2"
            every { effectiveName } returns "Alice"
            every { effectiveAvatarUrl } returns "https://example.com/b.png"
        }
        val bot = mockk<Member>(relaxed = true) {
            every { user } returns botUser
            every { id } returns "3"
            every { effectiveName } returns "BotMaster"
        }

        every { jda.getGuildById(guildId) } returns guild
        every { guild.members } returns listOf(human1, human2, bot)

        val result = service.getGuildMembers(guildId)
        assertEquals(2, result.size, "Bots should be filtered out")
        assertEquals("Alice", result[0].name, "Should be sorted alphabetically")
        assertEquals("Zara", result[1].name)
    }

    // ---- getApprovedCountsForGuilds ----

    @Test
    fun `getApprovedCountsForGuilds returns correct counts per guild`() {
        every { excuseService.countApproved(1L) } returns 5L
        every { excuseService.countApproved(2L) } returns 10L
        val result = service.getApprovedCountsForGuilds(listOf(1L, 2L))
        assertEquals(mapOf(1L to 5, 2L to 10), result)
    }

    @Test
    fun `getApprovedCountsForGuilds returns empty map for empty input`() {
        assertTrue(service.getApprovedCountsForGuilds(emptyList()).isEmpty())
    }

    // ---- getPage ----

    @Test
    fun `getPage returns approved page for normal user requesting approved tab`() {
        val paged = PagedExcuses(
            rows = listOf(ExcuseDto(id = 1L, guildId = guildId, excuse = "I was late", approved = true)),
            page = 1, pageSize = 12, totalCount = 1L
        )
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        every { excuseService.listApprovedPaged(guildId, 1, 12) } returns paged
        every { jda.getGuildById(guildId) } returns null // author lookup

        val vm = service.getPage(guildId, "approved", null, 1, discordId)
        assertEquals("approved", vm.requestedTab)
        assertFalse(vm.isSuperUser)
        assertEquals(1, vm.rows.size)
    }

    @Test
    fun `getPage downgrades non-superuser from pending to approved tab`() {
        val paged = PagedExcuses(rows = emptyList(), page = 1, pageSize = 12, totalCount = 0L)
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        every { excuseService.listApprovedPaged(guildId, 1, 12) } returns paged

        val vm = service.getPage(guildId, "pending", null, 1, discordId)
        assertEquals("approved", vm.requestedTab, "Non-superuser requesting pending should be downgraded to approved")
        verify(exactly = 0) { excuseService.listPendingPaged(any(), any(), any()) }
    }

    @Test
    fun `getPage returns pending page for superuser requesting pending tab`() {
        val paged = PagedExcuses(
            rows = listOf(ExcuseDto(id = 2L, guildId = guildId, excuse = "Pending excuse", approved = false)),
            page = 1, pageSize = 12, totalCount = 1L
        )
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = true }
        every { excuseService.listPendingPaged(guildId, 1, 12) } returns paged
        every { jda.getGuildById(guildId) } returns null

        val vm = service.getPage(guildId, "pending", null, 1, discordId)
        assertEquals("pending", vm.requestedTab)
        assertTrue(vm.isSuperUser)
        assertEquals(1, vm.rows.size)
    }

    @Test
    fun `getPage uses search when query is non-blank`() {
        val paged = PagedExcuses(
            rows = listOf(ExcuseDto(id = 3L, guildId = guildId, excuse = "Dog ate my homework", approved = true)),
            page = 1, pageSize = 12, totalCount = 1L
        )
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        every { excuseService.searchApproved(guildId, "dog", 1, 12) } returns paged
        every { jda.getGuildById(guildId) } returns null

        val vm = service.getPage(guildId, "approved", "  dog  ", 1, discordId)
        assertEquals("dog", vm.query, "Query should be trimmed")
        assertEquals(1, vm.rows.size)
        verify(exactly = 1) { excuseService.searchApproved(guildId, "dog", 1, 12) }
    }

    @Test
    fun `getPage row canDelete and canApprove flags for superuser`() {
        val excuse = ExcuseDto(id = 5L, guildId = guildId, excuse = "Too tired", approved = false, authorDiscordId = 999L)
        val paged = PagedExcuses(rows = listOf(excuse), page = 1, pageSize = 12, totalCount = 1L)
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = true }
        every { excuseService.listPendingPaged(guildId, 1, 12) } returns paged
        every { jda.getGuildById(guildId) } returns null

        val vm = service.getPage(guildId, "pending", null, 1, discordId)
        val row = vm.rows.single()
        assertTrue(row.canDelete, "Superuser can delete any excuse")
        assertTrue(row.canApprove, "Superuser can approve pending excuse")
        assertFalse(row.isAuthor, "Requester is not the author")
    }

    @Test
    fun `getPage row author owns pending can delete but not approve`() {
        val excuse = ExcuseDto(id = 6L, guildId = guildId, excuse = "Running late", approved = false, authorDiscordId = discordId)
        val paged = PagedExcuses(rows = listOf(excuse), page = 1, pageSize = 12, totalCount = 1L)
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        every { excuseService.listApprovedPaged(guildId, 1, 12) } returns PagedExcuses(rows = emptyList(), page = 1, pageSize = 12, totalCount = 0L)
        // For the approved tab, use search or approved list — let's use getPage with approved tab
        // but inject our pending excuse in the approved response
        val paged2 = PagedExcuses(rows = listOf(excuse), page = 1, pageSize = 12, totalCount = 1L)
        every { excuseService.listApprovedPaged(guildId, 1, 12) } returns paged2
        every { jda.getGuildById(guildId) } returns null

        val vm = service.getPage(guildId, "approved", null, 1, discordId)
        val row = vm.rows.single()
        assertTrue(row.isAuthor)
        assertTrue(row.canDelete, "Author of pending excuse can delete")
        assertFalse(row.canApprove, "Non-superuser cannot approve")
    }

    // ---- getRandomApproved ----

    @Test
    fun `getRandomApproved returns null when no approved excuses`() {
        every { excuseService.listApprovedGuildExcuses(guildId) } returns emptyList()
        assertNull(service.getRandomApproved(guildId))
    }

    @Test
    fun `getRandomApproved returns a view when approved excuses exist`() {
        val excuse = ExcuseDto(id = 7L, guildId = guildId, excuse = "Alarm didn't go off", approved = true, author = "Bob", authorDiscordId = null)
        every { excuseService.listApprovedGuildExcuses(guildId) } returns listOf(excuse)
        every { jda.getGuildById(guildId) } returns null // author lookup falls through

        val result = service.getRandomApproved(guildId)
        assertNotNull(result)
        assertEquals(7L, result!!.id)
        assertEquals("Alarm didn't go off", result.text)
        assertEquals("Bob", result.author)
    }

    @Test
    fun `getRandomApproved resolves author from guild member effectiveName`() {
        val excuse = ExcuseDto(id = 8L, guildId = guildId, excuse = "Car trouble", approved = true, authorDiscordId = 301L)
        val guild = mockk<Guild>(relaxed = true)
        val member = mockk<Member>(relaxed = true) {
            every { effectiveName } returns "NickName"
        }
        every { excuseService.listApprovedGuildExcuses(guildId) } returns listOf(excuse)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(301L) } returns member

        val result = service.getRandomApproved(guildId)
        assertNotNull(result)
        assertEquals("NickName", result!!.author)
    }

    @Test
    fun `getRandomApproved falls back to JDA user name when member left guild`() {
        val excuse = ExcuseDto(id = 9L, guildId = guildId, excuse = "Flat tire", approved = true, authorDiscordId = 302L)
        val guild = mockk<Guild>(relaxed = true)
        val jdaUser = mockk<User>(relaxed = true) { every { name } returns "OldUserName" }
        every { excuseService.listApprovedGuildExcuses(guildId) } returns listOf(excuse)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(302L) } returns null
        every { jda.getUserById(302L) } returns jdaUser

        val result = service.getRandomApproved(guildId)
        assertNotNull(result)
        assertEquals("OldUserName", result!!.author)
    }

    @Test
    fun `getRandomApproved falls back to stored author when authorDiscordId is null`() {
        val excuse = ExcuseDto(id = 10L, guildId = guildId, excuse = "Old excuse", approved = true, author = "Legacy Author", authorDiscordId = null)
        every { excuseService.listApprovedGuildExcuses(guildId) } returns listOf(excuse)

        val result = service.getRandomApproved(guildId)
        assertNotNull(result)
        assertEquals("Legacy Author", result!!.author)
    }

    @Test
    fun `getRandomApproved returns Unknown when all author fields are null`() {
        val excuse = ExcuseDto(id = 11L, guildId = guildId, excuse = "Mysterious", approved = true, author = null, authorDiscordId = null)
        every { excuseService.listApprovedGuildExcuses(guildId) } returns listOf(excuse)

        val result = service.getRandomApproved(guildId)
        assertNotNull(result)
        assertEquals("Unknown", result!!.author)
    }

    // ---- submit ----

    @Test
    fun `submit returns error when text is blank`() {
        val result = service.submit(guildId, "   ", null, discordId)
        assertFalse(result.ok)
        assertEquals("Provide some excuse text.", result.error)
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
    }

    @Test
    fun `submit returns error when text exceeds max length`() {
        val longText = "a".repeat(201)
        val result = service.submit(guildId, longText, null, discordId)
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("too long"))
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
    }

    @Test
    fun `submit returns error when duplicate excuse already exists`() {
        val existing = ExcuseDto(id = 12L, guildId = guildId, excuse = "I was tired", approved = true)
        every { excuseService.listAllGuildExcuses(guildId) } returns listOf(existing)

        val result = service.submit(guildId, "I was tired", null, discordId)
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("already exists"))
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
    }

    @Test
    fun `submit case-insensitive duplicate check returns error`() {
        val existing = ExcuseDto(id = 13L, guildId = guildId, excuse = "I was tired", approved = false)
        every { excuseService.listAllGuildExcuses(guildId) } returns listOf(existing)

        val result = service.submit(guildId, "I WAS TIRED", null, discordId)
        assertFalse(result.ok)
        assertTrue(result.error!!.contains("already exists"))
    }

    @Test
    fun `submit creates excuse and returns id on success`() {
        every { excuseService.listAllGuildExcuses(guildId) } returns emptyList()
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        val guild = mockk<Guild>(relaxed = true)
        val member = mockk<Member>(relaxed = true) { every { effectiveName } returns "TestUser" }
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns member
        every { excuseService.createNewExcuse(any()) } returns
            ExcuseDto(id = 99L, guildId = guildId, excuse = "My dog ate it")

        val result = service.submit(guildId, "My dog ate it", null, discordId)
        assertTrue(result.ok)
        assertEquals(99L, result.id)
    }

    @Test
    fun `submit non-superuser ignores authorOverride and uses requesterDiscordId`() {
        every { excuseService.listAllGuildExcuses(guildId) } returns emptyList()
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        val guild = mockk<Guild>(relaxed = true)
        val member = mockk<Member>(relaxed = true) { every { effectiveName } returns "Requester" }
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns member
        every { excuseService.createNewExcuse(any()) } answers {
            firstArg<ExcuseDto>().also { it.id = 100L }
        }

        // Pass a different authorOverrideDiscordId (999L) — for non-superuser it should be ignored
        val result = service.submit(guildId, "My bus was late", 999L, discordId)
        assertTrue(result.ok)
        verify {
            excuseService.createNewExcuse(match { it.authorDiscordId == discordId })
        }
    }

    @Test
    fun `submit superuser can override authorDiscordId`() {
        val overrideId = 555L
        every { excuseService.listAllGuildExcuses(guildId) } returns emptyList()
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = true }
        val guild = mockk<Guild>(relaxed = true)
        val member = mockk<Member>(relaxed = true) { every { effectiveName } returns "OtherUser" }
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(overrideId) } returns member
        every { excuseService.createNewExcuse(any()) } answers {
            firstArg<ExcuseDto>().also { it.id = 101L }
        }

        val result = service.submit(guildId, "Weather was bad", overrideId, discordId)
        assertTrue(result.ok)
        verify {
            excuseService.createNewExcuse(match { it.authorDiscordId == overrideId })
        }
    }

    @Test
    fun `submit returns id null when createNewExcuse returns null`() {
        every { excuseService.listAllGuildExcuses(guildId) } returns emptyList()
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        val guild = mockk<Guild>(relaxed = true)
        val member = mockk<Member>(relaxed = true) { every { effectiveName } returns "User" }
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns member
        every { excuseService.createNewExcuse(any()) } returns null

        val result = service.submit(guildId, "Late again", null, discordId)
        assertTrue(result.ok, "No error means ok=true even if saved id is null")
        assertNull(result.id)
    }

    // ---- approve ----

    @Test
    fun `approve returns error when requester is not superuser`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        val error = service.approve(1L, discordId, guildId)
        assertEquals("You don't have permission to approve excuses.", error)
        verify(exactly = 0) { excuseService.approveExcuse(any()) }
    }

    @Test
    fun `approve returns error when excuse not found`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = true }
        every { excuseService.getExcuseById(1L) } returns null
        val error = service.approve(1L, discordId, guildId)
        assertEquals("Excuse not found.", error)
    }

    @Test
    fun `approve returns error when excuse belongs to different guild`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = true }
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = 999L, excuse = "Wrong guild", approved = false)
        val error = service.approve(1L, discordId, guildId)
        assertEquals("Excuse not found.", error)
    }

    @Test
    fun `approve returns null when excuse is already approved`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = true }
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = guildId, excuse = "Already done", approved = true)
        val error = service.approve(1L, discordId, guildId)
        assertNull(error)
        verify(exactly = 0) { excuseService.approveExcuse(any()) }
    }

    @Test
    fun `approve approves excuse and returns null on success`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = true }
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = guildId, excuse = "Valid excuse", approved = false)
        every { excuseService.approveExcuse(1L) } returns
            ExcuseDto(id = 1L, guildId = guildId, excuse = "Valid excuse", approved = true)
        val error = service.approve(1L, discordId, guildId)
        assertNull(error)
        verify(exactly = 1) { excuseService.approveExcuse(1L) }
    }

    @Test
    fun `approve returns error when approveExcuse returns null`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = true }
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = guildId, excuse = "Valid", approved = false)
        every { excuseService.approveExcuse(1L) } returns null
        val error = service.approve(1L, discordId, guildId)
        assertEquals("Excuse not found.", error)
    }

    // ---- delete ----

    @Test
    fun `delete returns error when excuse not found`() {
        every { excuseService.getExcuseById(1L) } returns null
        val error = service.delete(1L, discordId, guildId)
        assertEquals("Excuse not found.", error)
        verify(exactly = 0) { excuseService.deleteExcuseById(any()) }
    }

    @Test
    fun `delete returns error when excuse belongs to different guild`() {
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = 999L, excuse = "Wrong guild", approved = false)
        val error = service.delete(1L, discordId, guildId)
        assertEquals("Excuse not found.", error)
        verify(exactly = 0) { excuseService.deleteExcuseById(any()) }
    }

    @Test
    fun `delete returns permission error for non-superuser deleting others approved excuse`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = guildId, excuse = "Approved", approved = true, authorDiscordId = 999L)

        val error = service.delete(1L, discordId, guildId)
        assertEquals("You don't have permission to delete that excuse.", error)
        verify(exactly = 0) { excuseService.deleteExcuseById(any()) }
    }

    @Test
    fun `delete allows non-superuser to delete own pending excuse`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = guildId, excuse = "My excuse", approved = false, authorDiscordId = discordId)

        val error = service.delete(1L, discordId, guildId)
        assertNull(error)
        verify(exactly = 1) { excuseService.deleteExcuseById(1L) }
    }

    @Test
    fun `delete allows superuser to delete any excuse`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = true }
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = guildId, excuse = "Any excuse", approved = true, authorDiscordId = 999L)

        val error = service.delete(1L, discordId, guildId)
        assertNull(error)
        verify(exactly = 1) { excuseService.deleteExcuseById(1L) }
    }

    @Test
    fun `delete returns permission error for non-superuser on others pending excuse`() {
        every { userService.getUserById(discordId, guildId) } returns
            UserDto(discordId, guildId).apply { superUser = false }
        every { excuseService.getExcuseById(1L) } returns
            ExcuseDto(id = 1L, guildId = guildId, excuse = "Other's pending", approved = false, authorDiscordId = 999L)

        val error = service.delete(1L, discordId, guildId)
        assertEquals("You don't have permission to delete that excuse.", error)
        verify(exactly = 0) { excuseService.deleteExcuseById(any()) }
    }

    // ---- ExcusePageViewModel computed properties ----

    @Test
    fun `ExcusePageViewModel hasPrev and hasNext computed properties`() {
        val vm = ExcusePageViewModel(
            rows = emptyList(), page = 2, totalPages = 3,
            totalCount = 25L, isSuperUser = false, requestedTab = "approved", query = null
        )
        assertTrue(vm.hasPrev)
        assertTrue(vm.hasNext)
    }

    @Test
    fun `ExcusePageViewModel hasPrev is false on first page`() {
        val vm = ExcusePageViewModel(
            rows = emptyList(), page = 1, totalPages = 3,
            totalCount = 25L, isSuperUser = false, requestedTab = "approved", query = null
        )
        assertFalse(vm.hasPrev)
        assertTrue(vm.hasNext)
    }

    @Test
    fun `ExcusePageViewModel hasNext is false on last page`() {
        val vm = ExcusePageViewModel(
            rows = emptyList(), page = 3, totalPages = 3,
            totalCount = 25L, isSuperUser = false, requestedTab = "approved", query = null
        )
        assertTrue(vm.hasPrev)
        assertFalse(vm.hasNext)
    }

    // ---- SubmitResult computed properties ----

    @Test
    fun `SubmitResult ok is true when error is null`() {
        assertTrue(SubmitResult(id = 1L).ok)
    }

    @Test
    fun `SubmitResult ok is false when error is set`() {
        assertFalse(SubmitResult(error = "oops").ok)
    }
}
