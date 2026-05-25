package web.controller

import core.command.Command
import core.managers.CommandManager
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import web.util.displayName

/**
 * HTML view for the public commands wiki. Lives in its own [Controller] so
 * the JSON `/commands` endpoint on [CommandController] can stay a
 * `@RestController` while this one renders a Thymeleaf template that uses
 * the shared navbar fragment — keeping the navbar consistent with the rest
 * of the site instead of hand-rolling its own.
 *
 * The controller pre-bakes a view model ([CategoryView] / [CommandView] /
 * [OptionView]) so the template stays declarative: friendly type labels
 * ("text", "@user") and the per-card search key are computed once here
 * rather than via Thymeleaf expression gymnastics.
 */
@Controller
class CommandWikiController(
    private val commandManager: CommandManager
) {

    @GetMapping("/commands/wiki")
    fun wiki(
        @AuthenticationPrincipal user: OAuth2User?,
        model: Model
    ): String {
        val categories = CATEGORY_META.mapNotNull { meta ->
            val raw = meta.source(commandManager)
            if (raw.isEmpty()) return@mapNotNull null
            CategoryView(
                slug = meta.slug,
                title = meta.title,
                icon = meta.icon,
                blurb = meta.blurb,
                commands = raw.sortedBy { it.name }.map(::toView)
            )
        }

        model.addAttribute("categories", categories)
        model.addAttribute("totalCommandCount", categories.sumOf { it.commands.size })
        // Endpoint is permitAll, so OAuth principal may be null (anon user).
        // Navbar fragment handles the null case (renders Login button).
        if (user != null) model.addAttribute("username", user.displayName())
        return "commands"
    }

    /** Per-category emoji + blurb shown in the category header strip. */
    private data class CategoryMeta(
        val slug: String,
        val title: String,
        val icon: String,
        val blurb: String,
        val source: (CommandManager) -> List<Command>
    )

    private val CATEGORY_META = listOf(
        CategoryMeta("music", "Music", "🎵",
            "Play, queue, and control music in your server's voice channels.") { it.musicCommands },
        CategoryMeta("dnd", "D&D", "🎲",
            "Roll dice and look up D&D 5e content.") { it.dndCommands },
        CategoryMeta("moderation", "Moderation", "🛡️",
            "Ban, kick, mute, purge, and manage server permissions.") { it.moderationCommands },
        CategoryMeta("games", "Games", "🎮",
            "Casino minigames, table games, the lottery, and head-to-head matchups.") { it.gameCommands },
        CategoryMeta("economy", "Economy", "💰",
            "Wallet, tipping, TOBY market, titles, and price alerts.") { it.economyCommands },
        CategoryMeta("misc", "Miscellaneous", "🧰",
            "Polls, intros, achievements, and assorted small utilities.") { it.miscCommands },
        CategoryMeta("fetch", "Fetch", "🌐",
            "Pull memes, news, and quick lookups from the web.") { it.fetchCommands }
    )

    private fun toView(command: Command): CommandView {
        val options = command.optionData.map(::toView)
        val subs = command.subCommands.map(::toView)
        val searchKey = buildString {
            append(command.name.lowercase())
            append(' ')
            append(command.description.lowercase())
            options.forEach { append(' ').append(it.name.lowercase()) }
            subs.forEach { sub ->
                append(' ').append(sub.name.lowercase())
                sub.options.forEach { append(' ').append(it.name.lowercase()) }
            }
        }
        return CommandView(
            name = command.name,
            description = command.description,
            options = options,
            subCommands = subs,
            searchKey = searchKey
        )
    }

    private fun toView(option: OptionData) = OptionView(
        name = option.name,
        description = option.description,
        typeLabel = friendlyTypeLabel(option.type),
        required = option.isRequired,
        choices = option.choices.map { it.name }
    )

    private fun toView(sub: SubcommandData) = SubcommandView(
        name = sub.name,
        description = sub.description,
        options = sub.options.map(::toView)
    )

    /** JDA enum → label a non-developer can parse at a glance. */
    private fun friendlyTypeLabel(type: OptionType): String = when (type) {
        OptionType.STRING -> "text"
        OptionType.INTEGER -> "number"
        OptionType.BOOLEAN -> "yes / no"
        OptionType.USER -> "@user"
        OptionType.CHANNEL -> "#channel"
        OptionType.ROLE -> "role"
        OptionType.MENTIONABLE -> "mention"
        OptionType.NUMBER -> "decimal"
        OptionType.ATTACHMENT -> "file"
        else -> type.name.lowercase().replace('_', ' ')
    }

    data class CategoryView(
        val slug: String,
        val title: String,
        val icon: String,
        val blurb: String,
        val commands: List<CommandView>
    )

    data class CommandView(
        val name: String,
        val description: String,
        val options: List<OptionView>,
        val subCommands: List<SubcommandView>,
        val searchKey: String
    ) {
        val hasDetails: Boolean get() = options.isNotEmpty() || subCommands.isNotEmpty()
    }

    data class OptionView(
        val name: String,
        val description: String,
        val typeLabel: String,
        val required: Boolean,
        val choices: List<String>
    )

    data class SubcommandView(
        val name: String,
        val description: String,
        val options: List<OptionView>
    )
}
