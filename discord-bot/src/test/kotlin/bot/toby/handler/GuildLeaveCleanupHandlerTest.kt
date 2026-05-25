package bot.toby.handler

import bot.toby.notify.AntiAutoclickNotifier
import bot.toby.voice.VoiceCompanyTracker
import database.blackjack.BlackjackTableRegistry
import database.poker.CasinoHoldemTableRegistry
import database.poker.PokerTableRegistry
import database.service.casino.CasinoBotSuspicionService
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

    private val handler = GuildLeaveCleanupHandler(
        pokerTableRegistry,
        blackjackTableRegistry,
        casinoHoldemTableRegistry,
        musicSseService,
        antiAutoclickNotifier,
        casinoBotSuspicionService,
        voiceCompanyTracker,
    )

    private fun leaveEvent(guildId: Long): GuildLeaveEvent {
        val guild = mockk<Guild>(relaxed = true) { every { idLong } returns guildId }
        return mockk(relaxed = true) { every { this@mockk.guild } returns guild }
    }

    @Test
    fun `guild leave evicts the leaving guild from every per-guild cache`() {
        handler.onGuildLeave(leaveEvent(42L))

        verify(exactly = 1) { pokerTableRegistry.evictGuild(42L) }
        verify(exactly = 1) { blackjackTableRegistry.evictGuild(42L) }
        verify(exactly = 1) { casinoHoldemTableRegistry.evictGuild(42L) }
        verify(exactly = 1) { musicSseService.evictGuild(42L) }
        verify(exactly = 1) { antiAutoclickNotifier.evictGuild(42L) }
        verify(exactly = 1) { casinoBotSuspicionService.evictGuild(42L) }
        verify(exactly = 1) { voiceCompanyTracker.evictGuild(42L) }
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
