package bot.toby.handler

import bot.toby.notify.AntiAutoclickNotifier
import bot.toby.voice.VoiceCompanyTracker
import database.blackjack.BlackjackTableRegistry
import database.poker.CasinoHoldemTableRegistry
import database.poker.PokerTableRegistry
import database.dto.guild.ConfigDto.Configurations
import database.service.activity.InstallEventService
import database.service.casino.CasinoBotSuspicionService
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import io.mockk.junit5.MockKExtension
import web.service.MusicSseService

@ExtendWith(MockKExtension::class)
class GuildLeaveCleanupHandlerTest {

    private val pokerTableRegistry: PokerTableRegistry = mockk(relaxed = true)
    private val blackjackTableRegistry: BlackjackTableRegistry = mockk(relaxed = true)
    private val casinoHoldemTableRegistry: CasinoHoldemTableRegistry = mockk(relaxed = true)
    private val musicSseService: MusicSseService = mockk(relaxed = true)
    private val antiAutoclickNotifier: AntiAutoclickNotifier = mockk(relaxed = true)
    private val casinoBotSuspicionService: CasinoBotSuspicionService = mockk(relaxed = true)
    private val voiceCompanyTracker: VoiceCompanyTracker = mockk(relaxed = true)
    private val installEventService: InstallEventService = mockk(relaxed = true)
    private val configService: ConfigService = mockk(relaxed = true)

    private val handler = GuildLeaveCleanupHandler(
        pokerTableRegistry,
        blackjackTableRegistry,
        casinoHoldemTableRegistry,
        musicSseService,
        antiAutoclickNotifier,
        casinoBotSuspicionService,
        voiceCompanyTracker,
        installEventService,
        configService,
    )

    private fun leaveEvent(guildId: Long): GuildLeaveEvent {
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
        }
        return mockk(relaxed = true) { every { this@mockk.guild } returns guild }
    }

    @Test
    fun `guild leave evicts the leaving guild from every per-guild cache`() {
        handler.onGuildLeave(leaveEvent(42L))

        verify(exactly = 1) { installEventService.recordLeave(42L, any()) }
        verify(exactly = 1) { pokerTableRegistry.evictGuild(42L) }
        verify(exactly = 1) { blackjackTableRegistry.evictGuild(42L) }
        verify(exactly = 1) { casinoHoldemTableRegistry.evictGuild(42L) }
        verify(exactly = 1) { musicSseService.evictGuild(42L) }
        verify(exactly = 1) { antiAutoclickNotifier.evictGuild(42L) }
        verify(exactly = 1) { casinoBotSuspicionService.evictGuild(42L) }
        verify(exactly = 1) { voiceCompanyTracker.evictGuild(42L) }
    }

    @Test
    fun `guild leave clears the INSTALL_MODE welcome-gate so a re-invite re-onboards`() {
        // INSTALL_MODE is the sentinel InstallWelcomeHandler checks to decide
        // whether to skip the welcome. Clearing it on leave is what lets a
        // re-invited guild see the wizard again instead of silence.
        handler.onGuildLeave(leaveEvent(42L))

        verify(exactly = 1) { configService.deleteConfig("42", Configurations.INSTALL_MODE.configValue) }
        // INSTALLED_AT and substantive config are preserved — never deleted here.
        verify(exactly = 0) { configService.deleteConfig("42", Configurations.INSTALLED_AT.configValue) }
        verify(exactly = 0) { configService.deleteAll(any()) }
    }

    @Test
    fun `a single registry failing does not block eviction of the others`() {
        // Each evict is wrapped in runCatching in the handler — a faulty
        // registry must not prevent the remaining ones from being cleaned.
        every { blackjackTableRegistry.evictGuild(any()) } throws RuntimeException("boom")

        handler.onGuildLeave(leaveEvent(42L))

        verify(exactly = 1) { pokerTableRegistry.evictGuild(42L) }
        verify(exactly = 1) { blackjackTableRegistry.evictGuild(42L) }
        verify(exactly = 1) { casinoHoldemTableRegistry.evictGuild(42L) }
        verify(exactly = 1) { musicSseService.evictGuild(42L) }
        verify(exactly = 1) { antiAutoclickNotifier.evictGuild(42L) }
        verify(exactly = 1) { casinoBotSuspicionService.evictGuild(42L) }
        verify(exactly = 1) { voiceCompanyTracker.evictGuild(42L) }
    }
}
