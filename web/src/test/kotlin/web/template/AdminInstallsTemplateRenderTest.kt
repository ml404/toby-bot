package web.template

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
import web.service.AdminInstallsService
import web.service.InstallChartsService

/**
 * Renders the real `templates/admin-installs.html` against a Spring-backed
 * Thymeleaf engine (same SpringEL dialect prod uses) so the stats strip,
 * the CSS bar chart, and the per-row expressions — the mode-badge
 * `th:classappend` ternary, the icon/placeholder branch, the owner-name
 * fallback, the metric chips, and the installed-at column — are all
 * exercised, not just the controller wiring.
 */
class AdminInstallsTemplateRenderTest {

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
        installs: List<AdminInstallsService.InstallRow>,
        stats: AdminInstallsService.InstallStats = statsFor(installs),
        chart: InstallChartsService.InstallChartView = emptyChart(),
    ): String {
        val request = MockHttpServletRequest(servletContext)
        val response = MockHttpServletResponse()
        val exchange = webApp.buildExchange(request, response)
        val ctx = WebContext(exchange).apply {
            setVariable("installs", installs)
            setVariable("stats", stats)
            setVariable("chart", chart)
            setVariable("username", "operator")
            setVariable("isBotOwner", true)
        }
        return engine.process("admin-installs", ctx)
    }

    private fun statsFor(installs: List<AdminInstallsService.InstallRow>) =
        AdminInstallsService.InstallStats(
            totalInstalls = installs.size,
            expressCount = installs.count { it.installMode == "express" },
            customCount = installs.count { it.installMode == "custom" },
            legacyCount = installs.count { it.installMode == AdminInstallsService.LEGACY },
            totalMembers = installs.sumOf { it.memberCount.toLong() },
            avgMembers = if (installs.isEmpty()) 0 else installs.sumOf { it.memberCount.toLong() } / installs.size,
            installsLast7Days = 1, installsLast30Days = 2,
            lifetimeJoins = 0, lifetimeLeaves = 0, netGrowth = 0,
            joinsLast30Days = 0, leavesLast30Days = 0, hasLedgerData = false,
        )

    private fun emptyChart() = InstallChartsService.InstallChartView(
        buckets = emptyList(), maxValue = 0, totalInstalls = 0, totalRemovals = 0,
    )

    private fun chartWith(vararg buckets: InstallChartsService.MonthBucket) =
        InstallChartsService.InstallChartView(
            buckets = buckets.toList(),
            maxValue = buckets.maxOfOrNull { maxOf(it.installs, it.removals) } ?: 0,
            totalInstalls = buckets.sumOf { it.installs },
            totalRemovals = buckets.sumOf { it.removals },
        )

    private fun row(
        guildId: String,
        name: String,
        ownerId: String = "1",
        ownerName: String? = "Owner",
        icon: String? = null,
        mode: String = "express",
        installedAt: Long? = 1_700_000_000_000L,
        boostTier: Int = 0,
        features: List<String> = emptyList(),
        daysSinceInstall: Long? = 42L,
        serverAgeDays: Long? = 365L,
    ) = AdminInstallsService.InstallRow(
        guildId = guildId, guildName = name, iconUrl = icon,
        ownerId = ownerId, ownerName = ownerName, memberCount = 7,
        installMode = mode, installedAtMillis = installedAt,
        botJoinedAtMillis = installedAt, serverCreatedMillis = 1_600_000_000_000L,
        boostTier = boostTier, boostCount = if (boostTier > 0) 10 else 0,
        locale = "English (US)", channelCount = 12, roleCount = 8,
        features = features, daysSinceInstall = daysSinceInstall, serverAgeDays = serverAgeDays,
    )

    @Test
    fun `express and custom installs render their mode badges with the right modifier class`() {
        val html = render(listOf(row("10", "Alpha", mode = "express"), row("20", "Beta", mode = "custom")))
        assertTrue(html.contains("mode-express")) { "express badge class missing:\n$html" }
        assertTrue(html.contains("mode-custom")) { "custom badge class missing:\n$html" }
        assertTrue(html.contains("Alpha") && html.contains("Beta"))
    }

    @Test
    fun `legacy install renders the legacy badge and an em-dash for the missing date`() {
        val html = render(listOf(row("30", "Gamma", mode = AdminInstallsService.LEGACY, installedAt = null, daysSinceInstall = null)))
        assertTrue(html.contains("mode-legacy")) { "legacy badge class missing:\n$html" }
        assertTrue(html.contains("legacy/unknown"))
        assertTrue(html.contains("&mdash;") || html.contains("—")) { "missing-date dash not rendered:\n$html" }
    }

    @Test
    fun `uncached owner falls back to showing the owner id`() {
        val html = render(listOf(row("40", "Delta", ownerId = "9988", ownerName = null)))
        assertTrue(html.contains("9988")) { "owner id fallback missing:\n$html" }
    }

    @Test
    fun `guild without an icon renders the initial placeholder instead of an img`() {
        val html = render(listOf(row("50", "Echo", icon = null)))
        assertTrue(html.contains("installs-icon-placeholder")) { "icon placeholder missing:\n$html" }
    }

    @Test
    fun `metric chips and stats strip render the deducible server info`() {
        val html = render(listOf(row("60", "Foxtrot", boostTier = 2, features = listOf("Community", "Verified"))))
        assertTrue(html.contains("Tier 2")) { "boost chip missing:\n$html" }
        assertTrue(html.contains("Community") && html.contains("Verified")) { "feature chips missing:\n$html" }
        assertTrue(html.contains("English (US)")) { "locale chip missing:\n$html" }
        assertTrue(html.contains("Total installs")) { "stats strip missing:\n$html" }
        assertTrue(html.contains("Net growth")) { "churn stat missing:\n$html" }
    }

    @Test
    fun `chart renders month bars when data is present`() {
        val html = render(
            installs = listOf(row("70", "Golf")),
            chart = chartWith(
                InstallChartsService.MonthBucket("Jun 2026", installs = 3, removals = 1, net = 2, installsHeightPct = 100, removalsHeightPct = 33),
            ),
        )
        assertTrue(html.contains("chart-bar-installs")) { "install bar missing:\n$html" }
        assertTrue(html.contains("chart-bar-removals")) { "removal bar missing:\n$html" }
        assertTrue(html.contains("Jun 2026")) { "month label missing:\n$html" }
    }

    @Test
    fun `chart shows the empty state when there is no history`() {
        val html = render(installs = listOf(row("80", "Hotel")), chart = emptyChart())
        assertTrue(html.contains("Not enough history yet")) { "chart empty state missing:\n$html" }
    }

    @Test
    fun `empty install list renders the empty-state hero`() {
        val html = render(emptyList())
        assertTrue(html.contains("No installs yet")) { "empty hero missing:\n$html" }
        assertFalse(html.contains("<table")) { "table should not render when there are no installs:\n$html" }
    }
}
