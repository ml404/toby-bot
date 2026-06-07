package web.service

import database.dto.activity.InstallEventType
import database.dto.guild.ConfigDto
import database.persistence.activity.MessageDailyCountPersistence
import database.service.activity.InstallEventService
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class AdminInstallsServiceTest {

    private val now = Instant.parse("2026-06-06T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private lateinit var jda: JDA
    private lateinit var configService: ConfigService
    private lateinit var installEventService: InstallEventService
    private lateinit var messageDailyCounts: MessageDailyCountPersistence
    private lateinit var service: AdminInstallsService

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        installEventService = mockk(relaxed = true)
        messageDailyCounts = mockk(relaxed = true)
        every { messageDailyCounts.findLastActiveByGuild() } returns emptyMap()
        service = AdminInstallsService(jda, configService, installEventService, messageDailyCounts, clock)
    }

    /** Strict guild mock — detail accessors are intentionally left unstubbed
     *  (they throw and the service's runCatching falls back to defaults),
     *  so these tests exercise the core install fields only. */
    private fun guild(
        id: String,
        name: String,
        ownerId: Long,
        ownerName: String?,
        members: Int = 5,
        icon: String? = null,
    ): Guild = mockk {
        every { this@mockk.id } returns id
        every { idLong } returns id.toLong()
        every { this@mockk.name } returns name
        every { iconUrl } returns icon
        every { memberCount } returns members
        every { ownerIdLong } returns ownerId
        every { this@mockk.ownerId } returns ownerId.toString()
        every { getMemberById(ownerId) } returns
            ownerName?.let { n -> mockk<Member> { every { effectiveName } returns n } }
    }

    private fun stubGuilds(vararg guilds: Guild) {
        val cache = mockk<SnowflakeCacheView<Guild>>(relaxed = true)
        every { jda.guildCache } returns cache
        every { cache.iterator() } returns guilds.toMutableList().iterator()
    }

    private fun cfg(name: String, value: String, guildId: String) =
        ConfigDto(name, value, guildId)

    @Test
    fun `express install surfaces mode and timestamp with resolved owner name`() {
        stubGuilds(guild("10", "Alpha", ownerId = 1L, ownerName = "Alice"))
        every { configService.listAllConfig() } returns listOf(
            cfg(ConfigDto.Configurations.INSTALL_MODE.configValue, "express", "10"),
            cfg(ConfigDto.Configurations.INSTALLED_AT.configValue, "1700000000000", "10"),
        )

        val row = service.listInstalls().single()

        assertEquals("express", row.installMode)
        assertTrue(row.wizardCompleted)
        assertEquals(1700000000000L, row.installedAtMillis)
        assertEquals("Alice", row.ownerName)
        assertEquals("1", row.ownerId)
        assertEquals(5, row.memberCount)
    }

    @Test
    fun `guild without install sentinel is reported as legacy with no date`() {
        stubGuilds(guild("20", "Beta", ownerId = 2L, ownerName = "Bob"))
        every { configService.listAllConfig() } returns emptyList()

        val row = service.listInstalls().single()

        assertEquals(AdminInstallsService.LEGACY, row.installMode)
        assertNull(row.installedAtMillis)
        assertNull(row.daysSinceInstall)
        assertEquals("", row.installedAtDisplay)
    }

    @Test
    fun `uncached owner falls back to id-only with null name`() {
        stubGuilds(guild("30", "Gamma", ownerId = 3L, ownerName = null))
        every { configService.listAllConfig() } returns emptyList()

        val row = service.listInstalls().single()

        assertNull(row.ownerName)
        assertEquals("3", row.ownerId)
    }

    @Test
    fun `installed rows sort newest first and legacy sinks to the bottom`() {
        stubGuilds(
            guild("10", "Older", ownerId = 1L, ownerName = "A"),
            guild("20", "Newer", ownerId = 2L, ownerName = "B"),
            guild("30", "Legacy", ownerId = 3L, ownerName = "C"),
        )
        every { configService.listAllConfig() } returns listOf(
            cfg(ConfigDto.Configurations.INSTALL_MODE.configValue, "custom", "10"),
            cfg(ConfigDto.Configurations.INSTALLED_AT.configValue, "1000", "10"),
            cfg(ConfigDto.Configurations.INSTALL_MODE.configValue, "express", "20"),
            cfg(ConfigDto.Configurations.INSTALLED_AT.configValue, "2000", "20"),
        )

        assertEquals(listOf("Newer", "Older", "Legacy"), service.listInstalls().map { it.guildName })
    }

    @Test
    fun `the global all-guild config row is ignored when indexing installs`() {
        stubGuilds(guild("10", "Alpha", ownerId = 1L, ownerName = "Alice"))
        every { configService.listAllConfig() } returns listOf(
            cfg(ConfigDto.Configurations.INSTALL_MODE.configValue, "express", "all"),
        )

        assertEquals(AdminInstallsService.LEGACY, service.listInstalls().single().installMode)
    }

    @Test
    fun `config is read exactly once in bulk - never per guild`() {
        stubGuilds(
            guild("10", "Alpha", ownerId = 1L, ownerName = "A"),
            guild("20", "Beta", ownerId = 2L, ownerName = "B"),
        )
        every { configService.listAllConfig() } returns emptyList()

        service.listInstalls()

        verify(exactly = 1) { configService.listAllConfig() }
        verify(exactly = 0) { configService.getConfigByName(any(), any()) }
    }

    @Test
    fun `deducible server detail is read off the cached guild`() {
        val self = mockk<SelfMember> {
            every { timeJoined } returns OffsetDateTime.parse("2026-05-07T00:00:00Z") // 30 days before clock
        }
        val richGuild = mockk<Guild> {
            every { id } returns "10"
            every { idLong } returns 10L
            every { name } returns "Rich"
            every { iconUrl } returns null
            every { memberCount } returns 100
            every { ownerIdLong } returns 1L
            every { ownerId } returns "1"
            every { getMemberById(1L) } returns mockk<Member> { every { effectiveName } returns "Owner" }
            every { selfMember } returns self
            every { timeCreated } returns OffsetDateTime.parse("2025-06-06T00:00:00Z") // ~365 days old
            every { boostTier } returns Guild.BoostTier.TIER_2
            every { boostCount } returns 14
            every { locale } returns net.dv8tion.jda.api.interactions.DiscordLocale.ENGLISH_US
            every { textChannels } returns listOf(mockk(), mockk())
            every { voiceChannels } returns listOf(mockk())
            every { roles } returns listOf(mockk(), mockk(), mockk())
            every { features } returns setOf("COMMUNITY", "VERIFIED", "SOME_NOISY_FLAG")
        }
        stubGuilds(richGuild)
        every { configService.listAllConfig() } returns emptyList()

        val row = service.listInstalls().single()

        assertEquals(2, row.boostTier)
        assertEquals(14, row.boostCount)
        assertEquals(3, row.channelCount) // 2 text + 1 voice
        assertEquals(3, row.roleCount)
        assertEquals(365L, row.serverAgeDays)
        // Only the notable features surface, prettified; the noisy one is dropped.
        assertTrue(row.features.contains("Community"))
        assertTrue(row.features.contains("Verified"))
        assertTrue(row.features.none { it.contains("Noisy") })
    }

    @Test
    fun `buildStats summarises modes, members and recent installs from the clock window`() {
        // now = 2026-06-06. last7 cutoff = 2026-05-30, last30 = 2026-05-07.
        val justNow = Instant.parse("2026-06-05T00:00:00Z").toEpochMilli()       // within 7d
        val twoWeeks = Instant.parse("2026-05-23T00:00:00Z").toEpochMilli()      // within 30d, not 7d
        val ancient = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()       // outside both
        val rows = listOf(
            rowOf(mode = "express", installedAt = justNow, members = 100),
            rowOf(mode = "custom", installedAt = twoWeeks, members = 50),
            rowOf(mode = AdminInstallsService.LEGACY, installedAt = null, members = 30),
            rowOf(mode = "express", installedAt = ancient, members = 20),
        )
        every { installEventService.countByType(InstallEventType.JOIN) } returns 10L
        every { installEventService.countByType(InstallEventType.LEAVE) } returns 4L
        every { installEventService.countByTypeSince(InstallEventType.JOIN, any()) } returns 3L
        every { installEventService.countByTypeSince(InstallEventType.LEAVE, any()) } returns 1L

        val stats = service.buildStats(rows)

        assertEquals(4, stats.totalInstalls)
        assertEquals(2, stats.expressCount)
        assertEquals(1, stats.customCount)
        assertEquals(1, stats.legacyCount)
        assertEquals(200L, stats.totalMembers)
        assertEquals(50L, stats.avgMembers)
        assertEquals(1, stats.installsLast7Days)
        assertEquals(2, stats.installsLast30Days)
        assertEquals(10L, stats.lifetimeJoins)
        assertEquals(4L, stats.lifetimeLeaves)
        assertEquals(6L, stats.netGrowth)
        assertEquals(3L, stats.joinsLast30Days)
        assertEquals(1L, stats.leavesLast30Days)
        assertTrue(stats.hasLedgerData)
    }

    @Test
    fun `buildStats reports no ledger data when the ledger is empty`() {
        every { installEventService.countByType(any()) } returns 0L
        every { installEventService.countByTypeSince(any(), any()) } returns 0L

        val stats = service.buildStats(emptyList())

        assertEquals(0, stats.totalInstalls)
        assertEquals(0L, stats.avgMembers)
        assertEquals(false, stats.hasLedgerData)
    }

    private fun rowOf(
        mode: String = "express",
        installedAt: Long? = null,
        members: Int = 0,
        locale: String? = null,
        boostTier: Int = 0,
        healthIssues: List<String> = emptyList(),
        isDormant: Boolean = false,
    ): AdminInstallsService.InstallRow =
        AdminInstallsService.InstallRow(
            guildId = "g", guildName = "G", iconUrl = null, ownerId = "1", ownerName = null,
            memberCount = members, installMode = mode, installedAtMillis = installedAt,
            botJoinedAtMillis = null, serverCreatedMillis = null, boostTier = boostTier, boostCount = 0,
            locale = locale, channelCount = 0, roleCount = 0, features = emptyList(),
            daysSinceInstall = null, serverAgeDays = null,
            healthIssues = healthIssues, lastActiveMillis = null, isDormant = isDormant,
        )

    @Test
    fun `health issues are flagged when the bot lacks post and manage-roles perms`() {
        val self = mockk<net.dv8tion.jda.api.entities.SelfMember> {
            every { hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR) } returns false
            every { hasPermission(net.dv8tion.jda.api.Permission.MANAGE_ROLES) } returns false
            every { hasPermission(any<net.dv8tion.jda.api.entities.channel.middleman.GuildChannel>(), *anyVararg()) } returns false
        }
        val g = mockk<Guild> {
            every { id } returns "10"; every { idLong } returns 10L; every { name } returns "Locked"; every { iconUrl } returns null
            every { memberCount } returns 5; every { ownerIdLong } returns 1L; every { ownerId } returns "1"
            every { getMemberById(1L) } returns null
            every { selfMember } returns self
            every { systemChannel } returns null
            every { textChannels } returns emptyList()
            // detail accessors left to throw → defaults
            every { timeCreated } throws RuntimeException("n/a")
        }
        stubGuilds(g)
        every { configService.listAllConfig() } returns emptyList()

        val row = service.listInstalls().single()

        assertTrue(row.healthIssues.contains("Can't post"))
        assertTrue(row.healthIssues.contains("No Manage Roles"))
        assertEquals(false, row.isHealthy)
    }

    @Test
    fun `administrator permission short-circuits all health checks`() {
        val self = mockk<net.dv8tion.jda.api.entities.SelfMember> {
            every { hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR) } returns true
        }
        val g = mockk<Guild> {
            every { id } returns "10"; every { idLong } returns 10L; every { name } returns "Admin"; every { iconUrl } returns null
            every { memberCount } returns 5; every { ownerIdLong } returns 1L; every { ownerId } returns "1"
            every { getMemberById(1L) } returns null
            every { selfMember } returns self
            every { timeCreated } throws RuntimeException("n/a")
        }
        stubGuilds(g)
        every { configService.listAllConfig() } returns emptyList()

        assertTrue(service.listInstalls().single().isHealthy)
    }

    @Test
    fun `guild with recent message activity is not dormant`() {
        stubGuilds(guild("10", "Alpha", ownerId = 1L, ownerName = "A"))
        every { configService.listAllConfig() } returns emptyList()
        // clock is 2026-06-06; 3 days ago is well within the 30-day window.
        every { messageDailyCounts.findLastActiveByGuild() } returns mapOf(10L to java.time.LocalDate.of(2026, 6, 3))

        val row = service.listInstalls().single()

        assertEquals(false, row.isDormant)
        assertEquals(
            java.time.LocalDate.of(2026, 6, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            row.lastActiveMillis,
        )
    }

    @Test
    fun `guild with stale or missing activity is dormant`() {
        stubGuilds(
            guild("10", "Stale", ownerId = 1L, ownerName = "A"),
            guild("20", "Never", ownerId = 2L, ownerName = "B"),
        )
        every { configService.listAllConfig() } returns emptyList()
        every { messageDailyCounts.findLastActiveByGuild() } returns mapOf(10L to java.time.LocalDate.of(2026, 1, 1))

        val byName = service.listInstalls().associateBy { it.guildName }
        assertTrue(byName.getValue("Stale").isDormant)  // 5 months ago
        assertTrue(byName.getValue("Never").isDormant)  // no row at all
    }

    @Test
    fun `buildInsights counts feature adoption, size buckets and locale distribution`() {
        every { configService.listAllConfig() } returns listOf(
            cfg(ConfigDto.Configurations.ACTIVITY_TRACKING.configValue, "true", "10"),
            cfg(ConfigDto.Configurations.LOTTERY_DAILY_ENABLED.configValue, "true", "10"),
            cfg(ConfigDto.Configurations.WELCOME_ENABLED.configValue, "true", "20"),
            // config for a guild not in the row set must be ignored
            cfg(ConfigDto.Configurations.ACTIVITY_TRACKING.configValue, "true", "999"),
        )
        val rows = listOf(
            rowOf(members = 10, locale = "English (US)", boostTier = 0).copy(guildId = "10"),
            rowOf(members = 200, locale = "English (US)", boostTier = 2).copy(guildId = "20"),
            rowOf(members = 9000, locale = "German", boostTier = 0).copy(guildId = "30"),
        )

        val insights = service.buildInsights(rows)

        assertEquals(3, insights.total)
        val activity = insights.featureAdoption.first { it.label == "Activity tracking" }
        assertEquals(1, activity.count) // guild 10 only (999 ignored, no row)
        assertEquals(1, insights.featureAdoption.first { it.label == "Welcome messages" }.count)
        assertEquals(1, insights.sizeBuckets.first { it.label.startsWith("Small") }.count)
        assertEquals(1, insights.sizeBuckets.first { it.label.startsWith("Medium") }.count)
        assertEquals(1, insights.sizeBuckets.first { it.label.startsWith("Huge") }.count)
        assertEquals("English (US)", insights.localeDistribution.first().label)
        assertEquals(2, insights.localeDistribution.first().count)
        assertEquals(2, insights.boostDistribution.first { it.label == "No boost" }.count)
        assertEquals(1, insights.boostDistribution.first { it.label == "Tier 2" }.count)
    }
}
