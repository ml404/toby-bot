package bot.toby.managers

import core.modal.Modal
import core.modal.ModalContext
import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DefaultModalManagerTest {

    private class FakeModal(override val name: String) : Modal {
        override fun handle(ctx: ModalContext, deleteDelay: Int) = Unit
    }

    private fun manager(vararg modals: Modal): Pair<DefaultModalManager, ConfigService> {
        val configService = mockk<ConfigService>(relaxed = true)
        return DefaultModalManager(configService, modals.toList()) to configService
    }

    @Test
    fun `getModal resolves a stateful modalId via colon-prefix`() {
        val jackpot = FakeModal("jackpot")
        val (mgr, _) = manager(jackpot)

        // modalId styles seen in the wild: "jackpot:edit:42", "set-config:foo".
        assertEquals(jackpot, mgr.getModal("jackpot:edit:42"))
    }

    @Test
    fun `getModal matching is case-insensitive on the prefix`() {
        val jackpot = FakeModal("jackpot")
        val (mgr, _) = manager(jackpot)

        assertEquals(jackpot, mgr.getModal("JACKPOT:edit:42"))
    }

    @Test
    fun `getModal returns null when no modal matches`() {
        val (mgr, _) = manager(FakeModal("jackpot"))

        assertNull(mgr.getModal("nonexistent:edit:42"))
    }

    @Test
    fun `handle dispatches to the matched modal with the configured delete delay`() {
        val handled = slot<Int>()
        val jackpot = mockk<Modal>(relaxed = true) {
            every { name } returns "jackpot"
            every { handle(any(), capture(handled)) } returns Unit
        }
        val (mgr, configService) = manager(jackpot)
        every {
            configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "42")
        } returns ConfigDto("delete_delay", "30", "42")

        mgr.handle(event(modalId = "jackpot:edit:42", guildId = 42L))

        verify(exactly = 1) { jackpot.handle(any(), 30) }
        assertEquals(30, handled.captured)
    }

    @Test
    fun `handle defaults delete delay to 0 when no config is set`() {
        val handled = slot<Int>()
        val jackpot = mockk<Modal>(relaxed = true) {
            every { name } returns "jackpot"
            every { handle(any(), capture(handled)) } returns Unit
        }
        val (mgr, configService) = manager(jackpot)
        every {
            configService.getConfigByName(any(), any())
        } returns null

        mgr.handle(event(modalId = "jackpot:edit:42", guildId = 42L))

        verify(exactly = 1) { jackpot.handle(any(), 0) }
        assertEquals(0, handled.captured)
    }

    @Test
    fun `handle is a no-op when no modal matches the modalId`() {
        val jackpot = mockk<Modal>(relaxed = true) { every { name } returns "jackpot" }
        val (mgr, _) = manager(jackpot)

        mgr.handle(event(modalId = "unknown:whatever", guildId = 42L))

        verify(exactly = 0) { jackpot.handle(any(), any()) }
    }

    @Test
    fun `handle is a no-op when the event has no guild`() {
        val jackpot = mockk<Modal>(relaxed = true) { every { name } returns "jackpot" }
        val (mgr, _) = manager(jackpot)

        val ev = mockk<ModalInteractionEvent>(relaxed = true) {
            every { modalId } returns "jackpot:edit:42"
            every { guild } returns null
        }

        mgr.handle(ev)

        verify(exactly = 0) { jackpot.handle(any(), any()) }
    }

    private fun event(modalId: String, guildId: Long): ModalInteractionEvent {
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
        }
        return mockk(relaxed = true) {
            every { this@mockk.modalId } returns modalId
            every { this@mockk.guild } returns guild
        }
    }
}
