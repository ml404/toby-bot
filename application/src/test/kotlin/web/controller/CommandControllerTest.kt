package web.controller

import core.command.Command
import core.managers.CommandManager
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.collections.emptyList

class CommandControllerTest {
    private lateinit var commandManager: CommandManager
    private lateinit var controller: CommandController

    @BeforeEach
    fun setup() {
        commandManager = mockk(relaxed = true)
        controller = CommandController(commandManager)
    }

    @Test
    fun `getCommands returns mapped commands`() {
        val option = OptionData(OptionType.STRING, "testoption", "A test option")
        val mockCommand = mockk<Command>(relaxed = true) {
            every { name } returns "mockCommand"
            every { description } returns "Mock command description"
            every { optionData } returns listOf(option)
            every { subCommands } returns emptyList()
        }

        every { commandManager.commands } returns listOf(mockCommand)

        val result = controller.getCommands()

        assertEquals(1, result.size)
        val cmdDoc = result.first()
        assertEquals("mockCommand", cmdDoc.name)
        assertEquals("Mock command description", cmdDoc.description)
        assertEquals(1, cmdDoc.options.size)
        assertEquals("testoption", cmdDoc.options.first().name)
        assertTrue(cmdDoc.subCommands.isEmpty())
    }

    @Test
    fun `getCommands includes subcommands`() {
        val option = OptionData(OptionType.STRING, "suboption", "A sub option")
        // Mock subcommand
        val subCommand = SubcommandData("sub1", "sub desc").addOptions(option)
        val mockCommand = mockk<Command>(relaxed = true) {
            every { name } returns "mockCommand"
            every { description } returns "Mock command description"
            every { optionData } returns emptyList()
            every { subCommands } returns listOf(subCommand)
        }

        every { commandManager.commands } returns listOf(mockCommand)

        val result = controller.getCommands()
        val cmdDoc = result.first()

        assertEquals(1, cmdDoc.subCommands.size)
        val subDoc = cmdDoc.subCommands.first()
        assertEquals("sub1", subDoc.name)
        assertEquals("sub desc", subDoc.description)
        assertEquals(1, subDoc.options.size)
        assertEquals("suboption", subDoc.options.first().name)
    }

    @Test
    fun `getCommandsWiki returns HTML with command name`() {
        val mockCommand = mockk<Command>(relaxed = true) {
            every { name } returns "mockCommand"
            every { description } returns "Mock command description"
            every { optionData } returns emptyList()
            every { subCommands } returns emptyList()
        }

        every { commandManager.musicCommands } returns listOf(mockCommand)
        every { commandManager.dndCommands } returns emptyList()
        every { commandManager.moderationCommands } returns emptyList()
        every { commandManager.miscCommands } returns emptyList()
        every { commandManager.fetchCommands } returns emptyList()
        every { commandManager.commands } returns listOf(mockCommand)

        val html = controller.getCommandsWiki()

        assertTrue(html.contains("mockCommand"))
        assertTrue(html.contains("Mock command description"))
        assertTrue(html.contains("<table"))
        assertTrue(html.contains("</table>"))
    }
}