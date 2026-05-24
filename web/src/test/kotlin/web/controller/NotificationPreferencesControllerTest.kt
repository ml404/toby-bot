package web.controller

import common.notification.NotificationChannelKind
import common.notification.Surface
import database.dto.user.UserNotificationPrefDto
import database.service.user.UserNotificationPrefService
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.ConcurrentModel
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import web.util.DefaultGuildCookie
import web.util.GuildMembership

class NotificationPreferencesControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var jda: JDA
    private lateinit var membership: GuildMembership
    private lateinit var prefService: UserNotificationPrefService
    private lateinit var controller: NotificationPreferencesController
    private lateinit var user: OAuth2User
    private lateinit var guild: Guild
    private lateinit var request: HttpServletRequest

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        membership = mockk(relaxed = true)
        prefService = mockk(relaxed = true)
        controller = NotificationPreferencesController(jda, membership, prefService)
        user = mockk(relaxed = true) {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        guild = mockk(relaxed = true) {
            every { id } returns guildId.toString()
            every { idLong } returns guildId
            every { name } returns "Test Guild"
        }
        every { jda.getGuildById(guildId) } returns guild
        every { membership.isMember(discordId, guildId) } returns true
        request = mockk(relaxed = true) { every { cookies } returns null }
    }

    private fun cookieFor(id: Long) {
        every { request.cookies } returns arrayOf(Cookie(DefaultGuildCookie.COOKIE_NAME, id.toString()))
    }

    @Test
    fun `page redirects to login for unauthenticated requests`() {
        val ra = RedirectAttributesModelMap()
        val view = controller.page(guildId, user = null, model = ConcurrentModel(), ra = ra)
        assertEquals("redirect:/login", view)
    }

    @Test
    fun `page redirects to picker for non-members`() {
        every { membership.isMember(discordId, guildId) } returns false
        val ra = RedirectAttributesModelMap()
        val view = controller.page(guildId, user = user, model = ConcurrentModel(), ra = ra)
        assertEquals("redirect:/preferences/notifications", view)
        assertNotNull(ra.flashAttributes["error"])
    }

    @Test
    fun `page redirects to picker when guild not in JDA`() {
        every { jda.getGuildById(guildId) } returns null
        val ra = RedirectAttributesModelMap()
        val view = controller.page(guildId, user = user, model = ConcurrentModel(), ra = ra)
        assertEquals("redirect:/preferences/notifications", view)
    }

    @Test
    fun `page renders preferences-notifications template for a valid member`() {
        every { prefService.listForUser(discordId, guildId) } returns emptyList()
        val model = ConcurrentModel()
        val view = controller.page(guildId, user, model, RedirectAttributesModelMap())

        assertEquals("preferences-notifications", view)
        assertEquals(guildId, model.getAttribute("guildId"))
        assertEquals("Test Guild", model.getAttribute("guildName"))
        assertEquals("tester", model.getAttribute("username"))
        assertEquals(Surface.entries.map { it.name }, model.getAttribute("surfaces"))

        @Suppress("UNCHECKED_CAST")
        val matrix = model.getAttribute("matrix") as List<NotificationPreferencesController.MatrixRow>
        assertEquals(NotificationChannelKind.entries.size, matrix.size, "one row per kind")
    }

    @Test
    fun `every matrix row has one cell per Surface entry`() {
        every { prefService.listForUser(discordId, guildId) } returns emptyList()
        val model = ConcurrentModel()
        controller.page(guildId, user, model, RedirectAttributesModelMap())

        @Suppress("UNCHECKED_CAST")
        val matrix = model.getAttribute("matrix") as List<NotificationPreferencesController.MatrixRow>
        matrix.forEach { row ->
            assertEquals(
                Surface.entries.size, row.cells.size,
                "${row.kind} must have ${Surface.entries.size} cells (one per surface)"
            )
        }
    }

    @Test
    fun `unsupported (kind, surface) renders as Placeholder cell`() {
        every { prefService.listForUser(discordId, guildId) } returns emptyList()
        val model = ConcurrentModel()
        controller.page(guildId, user, model, RedirectAttributesModelMap())

        @Suppress("UNCHECKED_CAST")
        val matrix = model.getAttribute("matrix") as List<NotificationPreferencesController.MatrixRow>
        // INTRO_PROMPT only supports DM — CHANNEL and PUSH must be placeholders.
        val introRow = matrix.first { it.kind == NotificationChannelKind.INTRO_PROMPT.name }
        val channelCell = introRow.cells.first { it.surface == Surface.CHANNEL.name }
        val pushCell = introRow.cells.first { it.surface == Surface.PUSH.name }
        val dmCell = introRow.cells.first { it.surface == Surface.DM.name }
        assertTrue(
            channelCell is NotificationPreferencesController.MatrixCell.Placeholder,
            "INTRO_PROMPT × CHANNEL must be a Placeholder"
        )
        assertTrue(
            pushCell is NotificationPreferencesController.MatrixCell.Placeholder,
            "INTRO_PROMPT × PUSH must be a Placeholder"
        )
        assertTrue(
            dmCell is NotificationPreferencesController.MatrixCell.Toggle,
            "INTRO_PROMPT × DM must be a Toggle"
        )
    }

    @Test
    fun `explicit pref row turns isDefault=false on the matching cell`() {
        every { prefService.listForUser(discordId, guildId) } returns listOf(
            UserNotificationPrefDto(
                discordId = discordId, guildId = guildId,
                channelKind = NotificationChannelKind.ACHIEVEMENT_UNLOCK.name,
                surface = Surface.DM.name, optIn = false
            )
        )
        val model = ConcurrentModel()
        controller.page(guildId, user, model, RedirectAttributesModelMap())

        @Suppress("UNCHECKED_CAST")
        val matrix = model.getAttribute("matrix") as List<NotificationPreferencesController.MatrixRow>
        val achRow = matrix.first { it.kind == NotificationChannelKind.ACHIEVEMENT_UNLOCK.name }
        val dmCell = achRow.cells.first { it.surface == Surface.DM.name }
            as NotificationPreferencesController.MatrixCell.Toggle
        assertEquals(false, dmCell.optIn, "explicit row drives optIn=false")
        assertEquals(false, dmCell.isDefault, "explicit row turns isDefault=false")

        val channelCell = achRow.cells.first { it.surface == Surface.CHANNEL.name }
            as NotificationPreferencesController.MatrixCell.Toggle
        assertEquals(true, channelCell.optIn, "CHANNEL unaffected, defaults to opt-in")
        assertEquals(true, channelCell.isDefault, "no explicit CHANNEL row → isDefault true")
    }

    @Test
    fun `picker redirects to login for unauthenticated`() {
        assertEquals(
            "redirect:/login",
            controller.picker(user = null, pick = false, request = request, model = ConcurrentModel())
        )
    }

    @Test
    fun `picker renders guild list for multi-guild user without anchor`() {
        val otherGuild = mockk<Guild>(relaxed = true) {
            every { id } returns "999"
            every { idLong } returns 999L
            every { name } returns "Other Guild"
            every { iconUrl } returns null
        }
        every { jda.guilds } returns listOf(guild, otherGuild)
        every { guild.getMemberById(discordId) } returns mockk(relaxed = true)
        every { otherGuild.getMemberById(discordId) } returns mockk(relaxed = true)

        val model = ConcurrentModel()
        val view = controller.picker(user, pick = false, request = request, model = model)
        assertEquals("preferences-notifications-picker", view)

        @Suppress("UNCHECKED_CAST")
        val guilds = model.getAttribute("guilds") as List<NotificationPreferencesController.PickerGuild>
        // Sorted by name lowercase: "Other Guild" before "Test Guild".
        assertEquals(2, guilds.size)
        assertEquals("Other Guild", guilds[0].name)
        assertEquals("Test Guild", guilds[1].name)
    }

    @Test
    fun `picker filters guilds the user is not a member of`() {
        val stranger = mockk<Guild>(relaxed = true) {
            every { id } returns "999"
            every { name } returns "Stranger"
            every { iconUrl } returns null
            every { getMemberById(discordId) } returns null
        }
        // Add a third guild so the user has >1 mutual — otherwise the
        // single-mutual-guild auto-redirect (which is intentional) fires
        // and the picker never renders.
        val other = mockk<Guild>(relaxed = true) {
            every { id } returns "888"
            every { name } returns "Other"
            every { iconUrl } returns null
            every { getMemberById(discordId) } returns mockk(relaxed = true)
        }
        every { jda.guilds } returns listOf(guild, stranger, other)
        every { guild.getMemberById(discordId) } returns mockk(relaxed = true)

        val model = ConcurrentModel()
        controller.picker(user, pick = false, request = request, model = model)

        @Suppress("UNCHECKED_CAST")
        val guilds = model.getAttribute("guilds") as List<NotificationPreferencesController.PickerGuild>
        assertEquals(2, guilds.size)
        assertTrue(guilds.none { it.name == "Stranger" })
    }

    @Test
    fun `picker redirects to single mutual guild's matrix`() {
        every { jda.guilds } returns listOf(guild)
        every { guild.getMemberById(discordId) } returns mockk(relaxed = true)

        val view = controller.picker(user, pick = false, request = request, model = ConcurrentModel())
        assertEquals("redirect:/preferences/notifications/$guildId", view)
    }

    @Test
    fun `picker redirects to anchored guild when cookie set`() {
        val otherGuild = mockk<Guild>(relaxed = true) {
            every { id } returns "999"
            every { name } returns "Other"
            every { iconUrl } returns null
            every { getMemberById(discordId) } returns mockk(relaxed = true)
        }
        every { jda.guilds } returns listOf(guild, otherGuild)
        every { guild.getMemberById(discordId) } returns mockk(relaxed = true)
        cookieFor(999L)

        val view = controller.picker(user, pick = false, request = request, model = ConcurrentModel())
        assertEquals("redirect:/preferences/notifications/999", view)
    }

    @Test
    fun `picker renders picker list when pick=true even with anchor`() {
        val otherGuild = mockk<Guild>(relaxed = true) {
            every { id } returns "999"
            every { name } returns "Other"
            every { iconUrl } returns null
            every { getMemberById(discordId) } returns mockk(relaxed = true)
        }
        every { jda.guilds } returns listOf(guild, otherGuild)
        every { guild.getMemberById(discordId) } returns mockk(relaxed = true)
        cookieFor(999L)

        val view = controller.picker(user, pick = true, request = request, model = ConcurrentModel())
        assertEquals("preferences-notifications-picker", view)
    }

    @Test
    fun `picker ignores stale cookie pointing to non-shared guild`() {
        val otherGuild = mockk<Guild>(relaxed = true) {
            every { id } returns "999"
            every { name } returns "Other"
            every { iconUrl } returns null
            every { getMemberById(discordId) } returns mockk(relaxed = true)
        }
        every { jda.guilds } returns listOf(guild, otherGuild)
        every { guild.getMemberById(discordId) } returns mockk(relaxed = true)
        cookieFor(12345L)

        val view = controller.picker(user, pick = false, request = request, model = ConcurrentModel())
        assertEquals("preferences-notifications-picker", view)
    }
}
