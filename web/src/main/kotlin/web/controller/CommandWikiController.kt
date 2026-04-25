package web.controller

import core.command.Command
import core.managers.CommandManager
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
        val categories = listOf(
            CommandCategory("Music", commandManager.musicCommands),
            CommandCategory("DnD", commandManager.dndCommands),
            CommandCategory("Moderation", commandManager.moderationCommands),
            CommandCategory("Economy", commandManager.economyCommands),
            CommandCategory("Miscellaneous", commandManager.miscCommands),
            CommandCategory("Fetch", commandManager.fetchCommands)
        ).filter { it.commands.isNotEmpty() }
            .map { cat -> cat.copy(commands = cat.commands.sortedBy { it.name }) }

        model.addAttribute("categories", categories)
        // Endpoint is permitAll, so OAuth principal may be null (anon user).
        // Navbar fragment handles the null case (renders Login button).
        if (user != null) model.addAttribute("username", user.displayName())
        return "commands"
    }

    data class CommandCategory(
        val title: String,
        val commands: List<Command>
    )
}
