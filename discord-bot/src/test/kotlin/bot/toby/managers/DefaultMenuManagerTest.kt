package bot.toby.managers

import core.menu.Menu
import core.menu.MenuContext
import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.components.MessageTopLevelComponent
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DefaultMenuManagerTest {

    private class FakeMenu(override val name: String) : Menu {
        override fun handle(ctx: MenuContext, deleteDelay: Int) = Unit
    }

    private fun manager(vararg menus: Menu): Pair<DefaultMenuManager, ConfigService> {
        val configService = mockk<ConfigService>(relaxed = true)
        return DefaultMenuManager(configService, menus.toList()) to configService
    }

    @Test
    fun `getMenu resolves a stateful componentId containing the menu name`() {
        val intro = FakeMenu("intro")
        val (mgr, _) = manager(intro)

        // componentId style: "intro:set", "edit-intro:delete", etc.
        assertEquals(intro, mgr.getMenu("intro:set"))
    }

    @Test
    fun `getMenu matching is case-insensitive`() {
        val intro = FakeMenu("intro")
        val (mgr, _) = manager(intro)

        assertEquals(intro, mgr.getMenu("INTRO:SET"))
    }

    @Test
    fun `getMenu returns null when no menu matches`() {
        val (mgr, _) = manager(FakeMenu("intro"))

        assertNull(mgr.getMenu("nonexistent:whatever"))
    }

    @Test
    fun `handle dispatches to the matched menu with the configured delete delay`() {
        val handled = slot<Int>()
        val intro = mockk<Menu>(relaxed = true) {
            every { name } returns "intro"
            every { handle(any(), capture(handled)) } returns Unit
        }
        val (mgr, configService) = manager(intro)
        every {
            configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "42")
        } returns ConfigDto("delete_delay", "30", "42")

        mgr.handle(eventWithComponentId("intro:set", guildId = 42L))

        verify(exactly = 1) { intro.handle(any(), 30) }
        assertEquals(30, handled.captured)
    }

    @Test
    fun `handle is a no-op when no menu matches the componentId`() {
        val intro = mockk<Menu>(relaxed = true) { every { name } returns "intro" }
        val (mgr, _) = manager(intro)

        mgr.handle(eventWithComponentId("unknown:select", guildId = 42L))

        verify(exactly = 0) { intro.handle(any(), any()) }
    }

    @Test
    fun `getMenu among multiple menus returns the one whose name is contained in the componentId`() {
        val intro = FakeMenu("intro")
        val dnd = FakeMenu("dnd")
        val (mgr, _) = manager(intro, dnd)

        assertSame(dnd, mgr.getMenu("dnd:search"))
        assertSame(intro, mgr.getMenu("intro:set"))
    }

    private fun eventWithComponentId(
        componentId: String,
        guildId: Long,
    ): StringSelectInteractionEvent {
        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns guildId
            every { id } returns guildId.toString()
        }
        val member = mockk<Member>(relaxed = true)
        val channel = mockk<MessageChannelUnion>(relaxed = true)
        val message = mockk<Message>(relaxed = true) {
            every { components } returns emptyList()
        }
        val editAction = mockk<MessageEditAction>(relaxed = true)
        every {
            message.editMessageComponents(any<Collection<MessageTopLevelComponent>>())
        } returns editAction

        return mockk(relaxed = true) {
            every { this@mockk.componentId } returns componentId
            every { this@mockk.guild } returns guild
            every { this@mockk.member } returns member
            every { this@mockk.channel } returns channel
            every { this@mockk.message } returns message
        }
    }
}
