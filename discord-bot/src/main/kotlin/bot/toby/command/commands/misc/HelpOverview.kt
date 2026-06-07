package bot.toby.command.commands.misc

import bot.toby.command.commands.dnd.DnDCommand
import bot.toby.command.commands.economy.EconomyCommand
import bot.toby.command.commands.fetch.FetchCommand
import bot.toby.command.commands.game.pvp.GameCommand
import bot.toby.command.commands.moderation.ModerationCommand
import bot.toby.command.commands.mtg.MtgCommand
import bot.toby.command.commands.music.MusicCommand
import core.command.Command
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * Single source of truth for the "what can this bot do?" overview embed,
 * shared by the no-argument [HelpCommand] and the public
 * [bot.toby.install.button.InstallHelpButton] on the install welcome
 * message. Both surfaces answer the same first-time question — "what does
 * this thing do, and what can I try right now?" — so they build from the
 * same place.
 *
 * Commands are grouped by the marker interface they already implement, so
 * a newly added command shows up in the overview automatically with no
 * per-command bookkeeping.
 */
object HelpOverview {

    private const val WEBSITE_URL = "https://www.toby-bot.co.uk/"
    // The site's own polished, auto-generated commands page (categorized
    // cards + search) — not the bare GitHub wiki.
    private const val COMMANDS_URL = "toby-bot.co.uk/commands/wiki"
    private const val KOFI_URL = "ko-fi.com/fratlayton"
    private const val OTHER_LABEL = "📦 Everything else"

    private data class HelpCategory(val label: String, val matches: (Command) -> Boolean)

    private val CATEGORIES: List<HelpCategory> = listOf(
        HelpCategory("🎲 Casino & games") { it is GameCommand },
        HelpCategory("🎵 Music") { it is MusicCommand },
        HelpCategory("💰 Economy & coins") { it is EconomyCommand },
        HelpCategory("🛡️ Moderation") { it is ModerationCommand },
        HelpCategory("🎴 Magic: The Gathering") { it is MtgCommand },
        HelpCategory("🐉 Dungeons & Dragons") { it is DnDCommand },
        HelpCategory("🔎 Lookups & tools") { it is FetchCommand },
        HelpCategory("✨ Profile & misc") { it is MiscCommand },
    )

    /**
     * Build the categorized overview embed for [commands]. Leads with a
     * zero-setup "try this now" nudge so a curious member has an immediate
     * action, then lists each command by name under its category.
     */
    fun embed(commands: List<Command>): MessageEmbed {
        val buckets = LinkedHashMap<String, MutableList<String>>()
        CATEGORIES.forEach { buckets[it.label] = mutableListOf() }
        buckets[OTHER_LABEL] = mutableListOf()

        commands.sortedBy { it.name }.forEach { cmd ->
            val label = CATEGORIES.firstOrNull { it.matches(cmd) }?.label ?: OTHER_LABEL
            buckets.getValue(label).add("`/${cmd.name}`")
        }

        val builder = EmbedBuilder()
            .setTitle("Toby Bot — what I can do")
            .setDescription(
                "**👉 New here? Try `/blackjack solo` or `/play <song>` right now — no setup needed.**\n\n" +
                    "Below is everything I offer, grouped by area. Run `/help <command>` for details on any one of them, " +
                    "or see the full feature tour at **[toby-bot.co.uk]($WEBSITE_URL)**."
            )
        buckets.filterValues { it.isNotEmpty() }.forEach { (label, names) ->
            builder.addField(label, names.joinToString(" · "), false)
        }
        builder.setFooter("Full command list: $COMMANDS_URL  ·  Enjoying the bot? Support dev at $KOFI_URL")
        return builder.build()
    }
}
