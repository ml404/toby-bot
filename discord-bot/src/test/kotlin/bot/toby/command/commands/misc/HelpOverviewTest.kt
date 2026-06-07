package bot.toby.command.commands.misc

import bot.toby.command.commands.music.player.PlayCommand
import core.command.Command
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class HelpOverviewTest {

    private val commands = listOf(PlayCommand())

    @Test
    fun `selectMenu offers a full-overview option plus each non-empty category`() {
        val menu = HelpOverview.selectMenu(commands)
        assertEquals(HelpOverview.MENU_ID, menu.customId)
        val values = menu.options.map { it.value }
        assertTrue(values.contains(HelpOverview.OVERVIEW_VALUE))
        // PlayCommand is a MusicCommand → the Music category shows up.
        assertTrue(values.contains("music"))
    }

    @Test
    fun `categoryEmbed for a category lists its commands with descriptions`() {
        val embed = HelpOverview.categoryEmbed("music", commands)
        assertTrue(embed.title!!.contains("Music"))
        assertTrue(embed.description!!.contains("/play"))
    }

    @Test
    fun `categoryEmbed for the overview value returns the full overview`() {
        val embed = HelpOverview.categoryEmbed(HelpOverview.OVERVIEW_VALUE, commands)
        assertTrue(embed.title!!.contains("what I can do"))
    }

    @Test
    fun `categoryEmbed for an unknown id falls back to the overview`() {
        val embed = HelpOverview.categoryEmbed("does-not-exist", commands)
        assertTrue(embed.title!!.contains("what I can do"))
    }

    @Test
    fun `commandDetailEmbed renders usage and arguments from option metadata`() {
        val cmd = mockk<Command> {
            every { name } returns "play"
            every { description } returns "Play a song"
            every { optionData } returns listOf(
                OptionData(OptionType.STRING, "song", "the song to play", true),
                OptionData(OptionType.INTEGER, "volume", "playback volume", false),
            )
            every { subCommands } returns emptyList()
        }

        val embed = HelpOverview.commandDetailEmbed(cmd)

        assertEquals("/play", embed.title)
        val usage = embed.fields.first { it.name == "Usage" }.value!!
        assertTrue(usage.contains("<song>"))     // required
        assertTrue(usage.contains("[volume]"))   // optional
        val args = embed.fields.first { it.name == "Arguments" }.value!!
        assertTrue(args.contains("song") && args.contains("required"))
        assertTrue(args.contains("volume") && args.contains("optional"))
    }
}
