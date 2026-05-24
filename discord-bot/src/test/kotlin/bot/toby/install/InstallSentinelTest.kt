package bot.toby.install

import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations
import database.service.guild.ConfigService
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallSentinelTest {

    private lateinit var configService: ConfigService

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
    }

    @Test
    fun `writes INSTALL_MODE and INSTALLED_AT when sentinel is unset`() {
        every {
            configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "g1")
        } returns null
        val rowsSlot = slot<List<Pair<String, String>>>()
        every {
            configService.upsertAll("g1", capture(rowsSlot))
        } returns emptyList()

        val before = System.currentTimeMillis()
        InstallSentinel.writeIfFresh(configService, "g1", mode = "express")
        val after = System.currentTimeMillis()

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        val rows = rowsSlot.captured
        assertTrue(rows.size == 2)
        assertTrue(rows[0] == Configurations.INSTALL_MODE.configValue to "express")
        assertTrue(rows[1].first == Configurations.INSTALLED_AT.configValue)
        assertTrue(rows[1].second.toLong() in before..after)
    }

    @Test
    fun `writes INSTALL_MODE=custom on first custom finish`() {
        every {
            configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "g1")
        } returns null
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll("g1", capture(rowsSlot)) } returns emptyList()

        InstallSentinel.writeIfFresh(configService, "g1", mode = "custom")

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        assertTrue(rowsSlot.captured[0] == Configurations.INSTALL_MODE.configValue to "custom")
    }

    @Test
    fun `no-op when INSTALL_MODE is already express`() {
        every {
            configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "g1")
        } returns ConfigDto(Configurations.INSTALL_MODE.configValue, "express", "g1")

        InstallSentinel.writeIfFresh(configService, "g1", mode = "custom")

        // Read only — never overwritten with "custom".
        verify(exactly = 1) {
            configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "g1")
        }
        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
        confirmVerified(configService)
    }

    @Test
    fun `no-op when INSTALL_MODE is already custom`() {
        every {
            configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "g1")
        } returns ConfigDto(Configurations.INSTALL_MODE.configValue, "custom", "g1")

        InstallSentinel.writeIfFresh(configService, "g1", mode = "express")

        verify(exactly = 0) { configService.upsertAll(any(), any()) }
        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `blank existing value is treated as unset and write proceeds`() {
        every {
            configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "g1")
        } returns ConfigDto(Configurations.INSTALL_MODE.configValue, "", "g1")
        val rowsSlot = slot<List<Pair<String, String>>>()
        every { configService.upsertAll("g1", capture(rowsSlot)) } returns emptyList()

        InstallSentinel.writeIfFresh(configService, "g1", mode = "express")

        verify(exactly = 1) { configService.upsertAll("g1", any()) }
        assertTrue(rowsSlot.captured[0] == Configurations.INSTALL_MODE.configValue to "express")
    }
}
