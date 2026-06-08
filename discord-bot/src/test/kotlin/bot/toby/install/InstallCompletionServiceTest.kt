package bot.toby.install

import database.achievement.AchievementCatalog
import database.dto.guild.ConfigDto
import database.dto.guild.ConfigDto.Configurations
import database.service.economy.JackpotService
import database.service.guild.AchievementService
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class InstallCompletionServiceTest {

    private lateinit var configService: ConfigService
    private lateinit var jackpotService: JackpotService
    private lateinit var achievementService: AchievementService
    private lateinit var service: InstallCompletionService
    private lateinit var guild: Guild

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        achievementService = mockk(relaxed = true)
        service = InstallCompletionService(configService, jackpotService, achievementService)
        guild = mockk(relaxed = true) {
            every { id } returns "g1"
            every { idLong } returns 1L
            every { ownerIdLong } returns 99L
        }
    }

    private fun stubInstalledAt(value: String?) {
        every {
            configService.getConfigByName(Configurations.INSTALLED_AT.configValue, "g1")
        } returns value?.let { ConfigDto(Configurations.INSTALLED_AT.configValue, it, "g1") }
    }

    @Test
    fun `first-ever install seeds jackpot, unlocks owner achievement, and stamps the sentinel`() {
        stubInstalledAt(null)

        service.complete(guild, mode = "express", channelId = 5L)

        verify(exactly = 1) {
            jackpotService.addToPool(1L, InstallCompletionService.JACKPOT_SEED_AMOUNT)
        }
        verify(exactly = 1) {
            achievementService.unlock(99L, 1L, InstallCompletionService.INSTALL_ACHIEVEMENT_CODE, 5L)
        }
        verify(exactly = 1) { configService.upsertAll("g1", any()) }
    }

    @Test
    fun `returning server (INSTALLED_AT survives) does not reseed the jackpot but still unlocks`() {
        stubInstalledAt("1700000000000")

        service.complete(guild, mode = "express", channelId = null)

        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
        verify(exactly = 1) {
            achievementService.unlock(99L, 1L, InstallCompletionService.INSTALL_ACHIEVEMENT_CODE, null)
        }
    }

    @Test
    fun `achievement failure is swallowed and the jackpot is still seeded`() {
        stubInstalledAt(null)
        every { achievementService.unlock(any(), any(), any(), any()) } throws RuntimeException("boom")

        service.complete(guild, mode = "custom", channelId = 5L)

        verify(exactly = 1) {
            jackpotService.addToPool(1L, InstallCompletionService.JACKPOT_SEED_AMOUNT)
        }
    }

    @Test
    fun `jackpot failure is swallowed`() {
        stubInstalledAt(null)
        every { jackpotService.addToPool(any(), any()) } throws RuntimeException("boom")

        // Must not propagate — the install UX completes regardless.
        service.complete(guild, mode = "express", channelId = 5L)
    }

    @Test
    fun `the install achievement code exists in the catalog`() {
        assertNotNull(AchievementCatalog.byCode(InstallCompletionService.INSTALL_ACHIEVEMENT_CODE))
    }
}
