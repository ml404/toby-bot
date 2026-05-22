package bot.toby.install

import database.dto.ConfigDto
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
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
        val installedAtSlot = slot<String>()
        every {
            configService.upsertConfig(Configurations.INSTALLED_AT.configValue, capture(installedAtSlot), "g1")
        } returns mockk(relaxed = true)

        val before = System.currentTimeMillis()
        InstallSentinel.writeIfFresh(configService, "g1", mode = "express")
        val after = System.currentTimeMillis()

        verify(exactly = 1) {
            configService.upsertConfig(Configurations.INSTALL_MODE.configValue, "express", "g1")
        }
        verify(exactly = 1) {
            configService.upsertConfig(Configurations.INSTALLED_AT.configValue, any<String>(), "g1")
        }
        assertTrue(installedAtSlot.captured.toLong() in before..after)
    }

    @Test
    fun `writes INSTALL_MODE=custom on first custom finish`() {
        every {
            configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "g1")
        } returns null

        InstallSentinel.writeIfFresh(configService, "g1", mode = "custom")

        verify(exactly = 1) {
            configService.upsertConfig(Configurations.INSTALL_MODE.configValue, "custom", "g1")
        }
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

        verify(exactly = 0) {
            configService.upsertConfig(any<String>(), any<String>(), any<String>())
        }
    }

    @Test
    fun `blank existing value is treated as unset and write proceeds`() {
        every {
            configService.getConfigByName(Configurations.INSTALL_MODE.configValue, "g1")
        } returns ConfigDto(Configurations.INSTALL_MODE.configValue, "", "g1")

        InstallSentinel.writeIfFresh(configService, "g1", mode = "express")

        verify(exactly = 1) {
            configService.upsertConfig(Configurations.INSTALL_MODE.configValue, "express", "g1")
        }
    }
}
