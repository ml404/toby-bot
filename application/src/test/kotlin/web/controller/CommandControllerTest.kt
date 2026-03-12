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

    private fun wikiWithMusicCommand(
        name: String = "mockCommand",
        description: String = "Mock command description",
        options: List<OptionData> = emptyList(),
        subCommands: List<SubcommandData> = emptyList()
    ): String {
        val mockCommand = mockk<Command>(relaxed = true) {
            every { this@mockk.name } returns name
            every { this@mockk.description } returns description
            every { optionData } returns options
            every { this@mockk.subCommands } returns subCommands
        }
        every { commandManager.musicCommands } returns listOf(mockCommand)
        every { commandManager.dndCommands } returns emptyList()
        every { commandManager.moderationCommands } returns emptyList()
        every { commandManager.miscCommands } returns emptyList()
        every { commandManager.fetchCommands } returns emptyList()
        return controller.getCommandsWiki()
    }

    @Test
    fun `getCommandsWiki returns valid HTML document`() {
        val html = wikiWithMusicCommand()

        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("<html"))
        assertTrue(html.contains("</html>"))
    }

    @Test
    fun `getCommandsWiki contains full navbar with brand and nav links`() {
        val html = wikiWithMusicCommand()

        assertTrue(html.contains("""class="brand""""))
        assertTrue(html.contains("""href="/commands/wiki""""))
        assertTrue(html.contains("""href="/intro/guilds""""))
        assertTrue(html.contains("""class="btn-discord""""))
    }

    @Test
    fun `getCommandsWiki renders command name as slash command with cmd class`() {
        val html = wikiWithMusicCommand(name = "mockCommand")

        assertTrue(html.contains("""/mockCommand"""))
        assertTrue(html.contains("""class="cmd""""))
    }

    @Test
    fun `getCommandsWiki renders command description`() {
        val html = wikiWithMusicCommand(description = "Mock command description")

        assertTrue(html.contains("Mock command description"))
        assertTrue(html.contains("""class="desc""""))
    }

    @Test
    fun `getCommandsWiki renders option with badge and opt-desc`() {
        val option = OptionData(OptionType.STRING, "myoption", "Option description")
        val html = wikiWithMusicCommand(options = listOf(option))

        assertTrue(html.contains("myoption"))
        assertTrue(html.contains("Option description"))
        assertTrue(html.contains("""class="badge">STRING"""))
        assertTrue(html.contains("""class="opt-desc""""))
    }

    @Test
    fun `getCommandsWiki renders option choices`() {
        val option = OptionData(OptionType.STRING, "myoption", "desc")
            .addChoices(net.dv8tion.jda.api.interactions.commands.Command.Choice("ChoiceOne", "choice_one"))
        val html = wikiWithMusicCommand(options = listOf(option))

        assertTrue(html.contains("ChoiceOne"))
        assertTrue(html.contains("""class="choice""""))
    }

    @Test
    fun `getCommandsWiki renders subcommands`() {
        val sub = SubcommandData("subname", "sub description")
        val html = wikiWithMusicCommand(subCommands = listOf(sub))

        assertTrue(html.contains("subname"))
        assertTrue(html.contains("sub description"))
    }

    @Test
    fun `getCommandsWiki renders em dash for command with no options`() {
        val html = wikiWithMusicCommand()

        assertTrue(html.contains("""class="none">&mdash;"""))
    }

    @Test
    fun `getCommandsWiki skips empty categories`() {
        val html = wikiWithMusicCommand()

        // Only Music was populated — DnD/Moderation/Misc/Fetch divs should be absent
        assertFalse(html.contains(">DnD<"))
        assertFalse(html.contains(">Moderation<"))
    }

    @Test
    fun `getCommandsWiki renders table structure`() {
        val html = wikiWithMusicCommand()

        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("</table>"))
        assertTrue(html.contains("""class="table-wrap""""))
    }

    @Test
    fun `getCommandsWiki sorts commands alphabetically within category`() {
        val cmdA = mockk<Command>(relaxed = true) {
            every { name } returns "zebra"
            every { description } returns "z"
            every { optionData } returns emptyList()
            every { subCommands } returns emptyList()
        }
        val cmdB = mockk<Command>(relaxed = true) {
            every { name } returns "alpha"
            every { description } returns "a"
            every { optionData } returns emptyList()
            every { subCommands } returns emptyList()
        }
        every { commandManager.musicCommands } returns listOf(cmdA, cmdB)
        every { commandManager.dndCommands } returns emptyList()
        every { commandManager.moderationCommands } returns emptyList()
        every { commandManager.miscCommands } returns emptyList()
        every { commandManager.fetchCommands } returns emptyList()

        val html = controller.getCommandsWiki()

        assertTrue(html.indexOf("/alpha") < html.indexOf("/zebra"))
    }
}