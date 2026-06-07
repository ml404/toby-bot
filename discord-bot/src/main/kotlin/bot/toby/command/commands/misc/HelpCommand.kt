package bot.toby.command.commands.misc

import bot.toby.command.commands.dnd.DnDCommand
import bot.toby.command.commands.economy.EconomyCommand
import bot.toby.command.commands.fetch.FetchCommand
import bot.toby.command.commands.game.pvp.GameCommand
import bot.toby.command.commands.moderation.ModerationCommand
import bot.toby.command.commands.mtg.MtgCommand
import bot.toby.command.commands.music.MusicCommand
import core.command.Command
import core.command.Command.Companion.replyEmbedAndDelete
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class HelpCommand @Autowired constructor(private val commands: List<Command>) : MiscCommand {
    companion object {
        private const val COMMAND = "command"
        private const val WIKI_URL = "github.com/ml404/toby-bot/wiki/Commands"
        private const val KOFI_URL = "ko-fi.com/fratlayton"

        /**
         * Display order + labels for the no-argument `/help` overview.
         * Each command is filed under the first category whose marker
         * interface it implements; anything that matches none falls through
         * to the "Everything else" bucket. Keeping this list here (rather
         * than a property on every Command) means new commands show up in
         * the overview automatically, grouped by the interface they already
         * implement — no per-command bookkeeping.
         */
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
        private const val OTHER_LABEL = "📦 Everything else"

        private data class HelpCategory(val label: String, val matches: (Command) -> Boolean)
    }

    override val ephemeral: Boolean = true

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        // No argument → show the in-Discord command overview rather than
        // punting to an external wiki. This is the surface a brand-new user
        // hits first, so it should answer "what can this bot do?" inline and
        // hand off a zero-setup first action.
        if (event.options.isEmpty()) {
            event.hook.replyEmbedAndDelete(overviewEmbed(), deleteDelay)
            return
        }

        val searchOptional = event.getOption(COMMAND)?.asString
        val command = getCommand(searchOptional!!)
        if (command == null) {
            event.hook.replyEphemeralAndDelete("Nothing found for command '$searchOptional'", deleteDelay)
            return
        }
        event.hook.replyEphemeralAndDelete("**/${command.name}** — ${command.description}", deleteDelay)
    }

    /**
     * Builds the categorized command overview. Commands are grouped by the
     * marker interface they implement and listed by name; the description
     * leads with a zero-setup "try this now" nudge so a curious member has
     * something to do immediately.
     */
    private fun overviewEmbed(): MessageEmbed {
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
                    "Below is everything I offer, grouped by area. Run `/help <command>` for details on any one of them."
            )
        buckets.filterValues { it.isNotEmpty() }.forEach { (label, names) ->
            builder.addField(label, names.joinToString(" · "), false)
        }
        builder.setFooter("Full docs: $WIKI_URL  ·  Enjoying the bot? Support dev at $KOFI_URL")
        return builder.build()
    }

    private fun getCommand(searchOptional: String) = commands.find { it.name.lowercase() == searchOptional }

    override val name: String
        get() = "help"
    override val description: String
        get() = "See everything the bot can do, or pass a command name for details on just that one."
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.STRING, COMMAND, "Command you would like help with", false, true))
}
