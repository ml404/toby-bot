package web.service

import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AdminInstallsServiceTest {

    private lateinit var jda: JDA
    private lateinit var configService: ConfigService
    private lateinit var service: AdminInstallsService

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        service = AdminInstallsService(jda, configService)
    }

    private fun guild(
        id: String,
        name: String,
        ownerId: Long,
        ownerName: String?,
        members: Int = 5,
        icon: String? = null,
    ): Guild = mockk {
        every { this@mockk.id } returns id
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

    @Test
    fun `express install surfaces mode and timestamp with resolved owner name`() {
        stubGuilds(guild("10", "Alpha", ownerId = 1L, ownerName = "Alice"))
        every { configService.listAllConfig() } returns listOf(
            ConfigDto(ConfigDto.Configurations.INSTALL_MODE.configValue, "express", "10"),
            ConfigDto(ConfigDto.Configurations.INSTALLED_AT.configValue, "1700000000000", "10"),
        )

        val rows = service.listInstalls()

        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("express", row.installMode)
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
            ConfigDto(ConfigDto.Configurations.INSTALL_MODE.configValue, "custom", "10"),
            ConfigDto(ConfigDto.Configurations.INSTALLED_AT.configValue, "1000", "10"),
            ConfigDto(ConfigDto.Configurations.INSTALL_MODE.configValue, "express", "20"),
            ConfigDto(ConfigDto.Configurations.INSTALLED_AT.configValue, "2000", "20"),
        )

        val names = service.listInstalls().map { it.guildName }

        assertEquals(listOf("Newer", "Older", "Legacy"), names)
    }

    @Test
    fun `the global all-guild config row is ignored when indexing installs`() {
        stubGuilds(guild("10", "Alpha", ownerId = 1L, ownerName = "Alice"))
        every { configService.listAllConfig() } returns listOf(
            // A global default sharing the INSTALL_MODE key name must not
            // leak onto a real guild that never ran the wizard.
            ConfigDto(ConfigDto.Configurations.INSTALL_MODE.configValue, "express", "all"),
        )

        val row = service.listInstalls().single()

        assertEquals(AdminInstallsService.LEGACY, row.installMode)
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
}
