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
import net.dv8tion.jda.api.components.selections.StringSelectMenu
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * Single source of truth for the "what can this bot do?" surfaces, shared
 * by the no-argument [HelpCommand], the public
 * [bot.toby.install.button.InstallHelpButton] on the install welcome
 * message, and the [bot.toby.menu.menus.HelpCategoryMenu] drill-down. They
 * all answer the same first-time question — "what does this thing do, and
 * what can I try right now?" — so they build from the same place.
 *
 * Commands are grouped by the marker interface they already implement, so
 * a newly added command shows up automatically with no per-command
 * bookkeeping.
 */
object HelpOverview {

    private const val WEBSITE_URL = "https://www.toby-bot.co.uk/"
    // The site's own polished, auto-generated commands page (categorized
    // cards + search) — not the bare GitHub wiki.
    private const val COMMANDS_URL = "toby-bot.co.uk/commands/wiki"
    private const val KOFI_URL = "ko-fi.com/fratlayton"

    /** componentId of the category drill-down select menu (routed to HelpCategoryMenu). */
    const val MENU_ID = "help_category"

    /** Select-menu value that returns to the full grouped overview. */
    const val OVERVIEW_VALUE = "overview"

    data class HelpCategory(val id: String, val label: String, val matches: (Command) -> Boolean)

    private val OTHER = HelpCategory("other", "📦 Everything else") { true }

    private val CATEGORIES: List<HelpCategory> = listOf(
        HelpCategory("casino", "🎲 Casino & games") { it is GameCommand },
        HelpCategory("music", "🎵 Music") { it is MusicCommand },
        HelpCategory("economy", "💰 Economy & coins") { it is EconomyCommand },
        HelpCategory("moderation", "🛡️ Moderation") { it is ModerationCommand },
        HelpCategory("mtg", "🎴 Magic: The Gathering") { it is MtgCommand },
        HelpCategory("dnd", "🐉 Dungeons & Dragons") { it is DnDCommand },
        HelpCategory("tools", "🔎 Lookups & tools") { it is FetchCommand },
        HelpCategory("profile", "✨ Profile & misc") { it is MiscCommand },
    )

    /**
     * Group [commands] into their categories, name-sorted, dropping empty
     * categories. Each command is filed under the first category whose
     * marker interface it matches; the rest fall through to "Everything
     * else".
     */
    private fun bucketed(commands: List<Command>): List<Pair<HelpCategory, List<Command>>> {
        val buckets = LinkedHashMap<HelpCategory, MutableList<Command>>()
        (CATEGORIES + OTHER).forEach { buckets[it] = mutableListOf() }
        commands.sortedBy { it.name }.forEach { cmd ->
            val cat = CATEGORIES.firstOrNull { it.matches(cmd) } ?: OTHER
            buckets.getValue(cat).add(cmd)
        }
        return buckets.entries.filter { it.value.isNotEmpty() }.map { it.key to it.value }
    }

    /**
     * The grouped overview embed: a zero-setup "try this now" nudge up top,
     * then each category listing its command names. Use the [selectMenu]
     * alongside it to let the reader drill into a category.
     */
    fun embed(commands: List<Command>): MessageEmbed {
        val builder = EmbedBuilder()
            .setTitle("Toby Bot — what I can do")
            .setDescription(
                "**👉 New here? Play `/blackjack solo` right now with your starter credits — " +
                    "top up anytime with `/daily`.**\n\n" +
                    "Below is everything I offer, grouped by area. Pick a category from the menu to see what each " +
                    "command does, run `/help <command>` for full usage, or take the feature tour at " +
                    "**[toby-bot.co.uk]($WEBSITE_URL)**."
            )
        bucketed(commands).forEach { (cat, cmds) ->
            builder.addField(cat.label, cmds.joinToString(" · ") { "`/${it.name}`" }, false)
        }
        builder.setFooter("Full command list: $COMMANDS_URL  ·  Enjoying the bot? Support dev at $KOFI_URL")
        return builder.build()
    }

    /**
     * The category drill-down menu. First option returns to the full
     * overview; the rest are the non-empty categories with a command count.
     */
    fun selectMenu(commands: List<Command>): StringSelectMenu {
        val builder = StringSelectMenu.create(MENU_ID).setPlaceholder("Jump to a category…")
        builder.addOption("📋 Full overview", OVERVIEW_VALUE, "Back to the grouped list of everything")
        bucketed(commands).forEach { (cat, cmds) ->
            builder.addOption(cat.label, cat.id, "${cmds.size} command${if (cmds.size == 1) "" else "s"}")
        }
        return builder.build()
    }

    /**
     * Embed for a single category, listing each command with its
     * description so a reader can tell at a glance what's worth running.
     * Falls back to the full [embed] for the overview value or an unknown
     * id.
     */
    fun categoryEmbed(categoryId: String, commands: List<Command>): MessageEmbed {
        if (categoryId == OVERVIEW_VALUE) return embed(commands)
        val (cat, cmds) = bucketed(commands).firstOrNull { it.first.id == categoryId } ?: return embed(commands)
        return EmbedBuilder()
            .setTitle(cat.label)
            .setDescription(cmds.joinToString("\n") { "`/${it.name}` — ${it.description}" })
            .setFooter("Pick another category below, or run /help <command> for full usage.")
            .build()
    }

    /**
     * Detail view for a single command: its description, a usage line built
     * from the declared options (`<required>` / `[optional]`), the argument
     * list, and any subcommands. Renders from the [Command.optionData] /
     * [Command.subCommands] metadata the command already declares.
     */
    fun commandDetailEmbed(command: Command): MessageEmbed {
        val builder = EmbedBuilder()
            .setTitle("/${command.name}")
            .setDescription(command.description)

        val options = command.optionData
        if (options.isNotEmpty()) {
            val usage = "/${command.name} " +
                options.joinToString(" ") { if (it.isRequired) "<${it.name}>" else "[${it.name}]" }
            builder.addField("Usage", "`${usage.trim()}`", false)
            builder.addField(
                "Arguments",
                options.joinToString("\n") { opt ->
                    val req = if (opt.isRequired) "required" else "optional"
                    val choices = opt.choices.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name }
                    val choiceSuffix = if (choices != null) " — choices: $choices" else ""
                    "`${opt.name}` ($req) — ${opt.description}$choiceSuffix"
                },
                false,
            )
        }

        val subs = command.subCommands
        if (subs.isNotEmpty()) {
            builder.addField(
                "Subcommands",
                subs.joinToString("\n") { "`/${command.name} ${it.name}` — ${it.description}" },
                false,
            )
        }

        builder.setFooter("Run /help for the full command list.")
        return builder.build()
    }
}
