package web.template

import database.dto.guild.ConfigDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import web.service.AutoRoleView
import web.service.CategoryInfo
import web.service.GuildOverview
import web.service.RoleInfo
import web.service.TextChannelInfo
import web.service.WelcomeOverview
import web.view.LotteryIncentivesView

/**
 * End-to-end Thymeleaf render of `templates/moderation/welcome.html`
 * against a Spring-backed template engine. Exercises:
 *  - the six welcome / goodbye `data-key` rows (presence + pre-population
 *    from `overview.config`),
 *  - the auto-role list rendering (empty state vs populated rows),
 *  - the role picker source list,
 *  - `isOwner=false` disabling every save / delete / add button,
 *  - the moderation header includes the Welcome nav link as active.
 *
 * Pure HTML render — no controller wiring. Catches regressions a
 * controller test cannot, like Thymeleaf expression typos in the
 * template, missing classes, or fragment misconfiguration.
 */
class WelcomeTemplateRenderTest {

    private val servletContext = MockServletContext()
    private val webApp = JakartaServletWebApplication.buildApplication(servletContext)

    private val engine: SpringTemplateEngine = SpringTemplateEngine().apply {
        val appCtx = GenericApplicationContext().also { it.refresh() }
        val resolver = SpringResourceTemplateResolver().apply {
            setApplicationContext(appCtx)
            prefix = "classpath:/templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
            isCacheable = false
        }
        setTemplateResolver(resolver)
    }

    private fun render(
        welcomeOverview: WelcomeOverview = defaultWelcomeOverview(),
        guildOverview: GuildOverview = defaultGuildOverview(),
        isOwner: Boolean = true,
    ): String {
        val request = MockHttpServletRequest(servletContext)
        val response = MockHttpServletResponse()
        val exchange = webApp.buildExchange(request, response)
        val ctx = WebContext(exchange).apply {
            setVariable("overview", guildOverview)
            setVariable("welcome", welcomeOverview)
            setVariable("isOwner", isOwner)
            setVariable("username", "tester")
            setVariable("actorDiscordId", "1")
            setVariable("jackpotPool", 0L)
            setVariable("errorMessage", null)
        }
        return engine.process("moderation/welcome", ctx)
    }

    // ---- presence regressions ----

    @Test
    fun `every welcome and goodbye config key has a data-key row`() {
        val html = render()
        val expectedKeys = listOf(
            ConfigDto.Configurations.WELCOME_ENABLED,
            ConfigDto.Configurations.WELCOME_CHANNEL,
            ConfigDto.Configurations.WELCOME_MESSAGE,
            ConfigDto.Configurations.GOODBYE_ENABLED,
            ConfigDto.Configurations.GOODBYE_CHANNEL,
            ConfigDto.Configurations.GOODBYE_MESSAGE,
        )
        for (key in expectedKeys) {
            assertTrue(
                html.contains("data-key=\"${key.name}\""),
                "welcome.html must carry a config-row for ${key.name} so admins can edit it",
            )
        }
    }

    @Test
    fun `welcome and goodbye sections each appear with details summary`() {
        val html = render()
        assertTrue(html.contains("<summary>Welcome announcement</summary>"))
        assertTrue(html.contains("<summary>Goodbye announcement</summary>"))
        assertTrue(html.contains("<summary>Auto-assigned roles on join</summary>"))
    }

    // ---- enabled-flag preselect ----

    @Test
    fun `WELCOME_ENABLED row preselects Enabled when config value is true`() {
        val overview = defaultGuildOverview(configOverrides = mapOf("WELCOME_ENABLED" to "true"))
        val html = render(guildOverview = overview)
        val row = rowOf(html, "WELCOME_ENABLED")
        // Thymeleaf renders th:selected="${...}" as a `selected` attribute
        // on the matching <option>. Pin that the "true" branch wins.
        val trueOptionIdx = row.indexOf("value=\"true\"")
        val falseOptionIdx = row.indexOf("value=\"false\"")
        val selectedIdx = row.indexOf("selected")
        assertTrue(selectedIdx in trueOptionIdx..(row.length))
        // sanity: false option shouldn't be selected
        val sliceUntilSelected = row.substring(falseOptionIdx, selectedIdx.coerceAtLeast(falseOptionIdx))
        assertFalse(sliceUntilSelected.contains(">selected") || sliceUntilSelected.contains("selected="),
            "false option must not be selected:\n$sliceUntilSelected")
    }

    @Test
    fun `WELCOME_ENABLED row preselects Disabled when config value is absent`() {
        val html = render()
        val row = rowOf(html, "WELCOME_ENABLED")
        val falseIdx = row.indexOf("value=\"false\"")
        val trueIdx = row.indexOf("value=\"true\"")
        val between = row.substring(falseIdx, trueIdx)
        assertTrue(between.contains("selected"),
            "false option should be selected when WELCOME_ENABLED is absent:\n$row")
    }

    // ---- message preselect ----

    @Test
    fun `WELCOME_MESSAGE row carries current value when configured`() {
        val msg = "Welcome to {server}, {user}!"
        val overview = defaultGuildOverview(configOverrides = mapOf("WELCOME_MESSAGE" to msg))
        val html = render(guildOverview = overview)
        val row = rowOf(html, "WELCOME_MESSAGE")
        assertTrue(row.contains("value=\"$msg\""), "input should pre-populate with current value:\n$row")
    }

    @Test
    fun `WELCOME_MESSAGE input is capped at 2000 chars in markup`() {
        val html = render()
        val row = rowOf(html, "WELCOME_MESSAGE")
        assertTrue(row.contains("maxlength=\"2000\""),
            "input must mirror the server-side 2000-char cap so browsers refuse longer text early:\n$row")
    }

    // ---- channel preselect ----

    @Test
    fun `WELCOME_CHANNEL select preselects the configured channel`() {
        val overview = defaultGuildOverview(configOverrides = mapOf("WELCOME_CHANNEL" to "555"))
        val html = render(guildOverview = overview)
        val row = rowOf(html, "WELCOME_CHANNEL")
        // Look for the option with id 555 carrying selected.
        val option555Idx = row.indexOf("value=\"555\"")
        assertTrue(option555Idx >= 0, "channel option 555 should render:\n$row")
        val tagEnd = row.indexOf(">", option555Idx)
        val tagSlice = row.substring(option555Idx, tagEnd)
        assertTrue(tagSlice.contains("selected"),
            "the 555 channel option must carry selected:\n$tagSlice")
    }

    // ---- auto-role list ----

    @Test
    fun `auto-role table renders one row per binding with role name`() {
        val welcomeOverview = WelcomeOverview(
            guildId = "1",
            guildName = "Test",
            autoRoles = listOf(
                AutoRoleView(roleId = "100", roleName = "Member", roleColorHex = "#ff9900", roleMissing = false),
                AutoRoleView(roleId = "200", roleName = "Verified", roleColorHex = null, roleMissing = false),
            ),
            roles = listOf(RoleInfo(id = "100", name = "Member"), RoleInfo(id = "200", name = "Verified")),
        )
        val html = render(welcomeOverview = welcomeOverview)
        assertTrue(html.contains("data-role-id=\"100\""))
        assertTrue(html.contains("data-role-id=\"200\""))
        assertTrue(html.contains(">Member<"))
        assertTrue(html.contains(">Verified<"))
    }

    @Test
    fun `auto-role empty state shows the no-bindings message`() {
        val welcomeOverview = WelcomeOverview(
            guildId = "1",
            guildName = "Test",
            autoRoles = emptyList(),
            roles = emptyList(),
        )
        val html = render(welcomeOverview = welcomeOverview)
        assertTrue(html.contains("No auto-roles bound yet."),
            "empty list should render the no-bindings placeholder")
    }

    @Test
    fun `missing-role rows surface a missing badge`() {
        val welcomeOverview = WelcomeOverview(
            guildId = "1",
            guildName = "Test",
            autoRoles = listOf(
                AutoRoleView(roleId = "999", roleName = "(deleted role)", roleColorHex = null, roleMissing = true),
            ),
            roles = emptyList(),
        )
        val html = render(welcomeOverview = welcomeOverview)
        assertTrue(html.contains("missing"),
            "missing role should carry a `missing` badge so admins can clean up:\n$html")
    }

    @Test
    fun `role picker dropdown is populated from welcome roles list`() {
        val welcomeOverview = WelcomeOverview(
            guildId = "1",
            guildName = "Test",
            autoRoles = emptyList(),
            roles = listOf(
                RoleInfo(id = "100", name = "Member"),
                RoleInfo(id = "200", name = "VIP"),
            ),
        )
        val html = render(welcomeOverview = welcomeOverview)
        assertTrue(html.contains("value=\"100\""))
        assertTrue(html.contains(">Member<"))
        assertTrue(html.contains("value=\"200\""))
        assertTrue(html.contains(">VIP<"))
    }

    // ---- non-owner disablement ----

    @Test
    fun `non-owner sees disabled inputs and buttons`() {
        val html = render(isOwner = false)
        // Every save button and form input should carry disabled.
        // Pin a sample to keep the test resilient to non-functional markup churn.
        val firstRowEnd = html.indexOf("</button>", html.indexOf("data-key=\"WELCOME_ENABLED\""))
        val firstRow = html.substring(
            html.indexOf("data-key=\"WELCOME_ENABLED\""),
            firstRowEnd,
        )
        assertTrue(firstRow.contains("disabled"),
            "non-owner: save button for WELCOME_ENABLED should be disabled:\n$firstRow")
    }

    // ---- navigation ----

    @Test
    fun `welcome tab is marked active in the moderation subnav`() {
        val html = render()
        // moderationHeader fragment renders an <a> with class.active when
        // active == 'welcome'. The Welcome page passes 'welcome' as the
        // active arg; pin that the active class lands on the welcome link.
        val anchorIdx = html.indexOf(">Welcome</a>")
        assertTrue(anchorIdx > 0, "Welcome subnav link must render")
        val openTagStart = html.lastIndexOf("<a ", anchorIdx)
        val anchor = html.substring(openTagStart, anchorIdx)
        assertTrue(anchor.contains("active"),
            "Welcome subnav link should carry the active class when active=='welcome':\n$anchor")
    }

    // ---- helpers ----

    private fun defaultWelcomeOverview() = WelcomeOverview(
        guildId = "1",
        guildName = "Test Guild",
        autoRoles = emptyList(),
        roles = listOf(RoleInfo(id = "100", name = "Member")),
    )

    private fun defaultGuildOverview(
        configOverrides: Map<String, String?> = emptyMap(),
    ): GuildOverview {
        val baseConfig = ConfigDto.Configurations.entries.associate { it.name to null as String? }
        val merged = baseConfig + configOverrides
        return GuildOverview(
            guildId = "1",
            guildName = "Test Guild",
            members = emptyList(),
            voiceChannels = emptyList(),
            textChannels = listOf(
                TextChannelInfo(id = "555", name = "welcomes"),
                TextChannelInfo(id = "666", name = "general"),
            ),
            categories = listOf(CategoryInfo(id = "1", name = "General")),
            config = merged,
            lotteryIncentives = LotteryIncentivesView.empty(),
        )
    }

    private fun rowOf(html: String, dataKey: String): String {
        val marker = "data-key=\"$dataKey\""
        val start = html.indexOf(marker).also {
            assertTrue(it >= 0, "row for $dataKey not found")
        }
        val rowStart = html.lastIndexOf("<div class=\"config-row\"", start)
        val rowEnd = html.indexOf("</div>", start) + "</div>".length
        return html.substring(rowStart, rowEnd)
    }
}
