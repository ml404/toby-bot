package web.controller

import core.command.Command
import core.managers.CommandManager
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/commands")
class CommandController(private val commandManager: CommandManager) {

    @Operation(summary = "Get all commands", description = "Fetch a list of all available commands with their metadata.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Successfully retrieved commands"),
        ApiResponse(responseCode = "500", description = "Internal server error")
    ])

    @GetMapping
    fun getCommands(): List<CommandDocumentation> {

        fun mapOptions(options: List<OptionData>): List<OptionDocumentation> =
            options.map { option ->
                OptionDocumentation(
                    name = option.name,
                    description = option.description,
                    type = option.type.name,
                    choices = option.choices.map { ChoiceDocumentation(it.name, it.asString) }
                )
            }

        return commandManager.commands.map { command ->
            CommandDocumentation(
                name = command.name,
                description = command.description,
                options = mapOptions(command.optionData),
                subCommands = command.subCommands.map { sub ->
                    SubcommandDocumentation(
                        name = sub.name,
                        description = sub.description,
                        options = mapOptions(sub.options)
                    )
                }
            )
        }
    }




    @Operation(summary = "Get command wiki", description = "Fetch an HTML representation of all available commands.")
    @GetMapping("/wiki", produces = ["text/html"])
    fun getCommandsWiki(): String {
        val html = StringBuilder()

        html.append("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>TobyBot &mdash; Commands</title>
                <link rel="icon" type="image/svg+xml" href="/images/favicon.svg">
                <link rel="stylesheet" href="/css/nav.css">
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #1a1a2e; color: #e0e0e0; min-height: 100vh; }
                    .container { max-width: 960px; margin: 40px auto; padding: 0 24px 80px; }
                    h1 { font-size: 1.8rem; color: #fff; margin-bottom: 8px; }
                    .subtitle { color: #a0a0b0; margin-bottom: 40px; }
                    .category { margin-bottom: 48px; }
                    h2 { font-size: 0.8rem; color: #a0a0b0; text-transform: uppercase; letter-spacing: 0.08em; margin-bottom: 12px; padding-bottom: 8px; border-bottom: 1px solid #2a2a4a; }
                    .table-wrap { background: #16213e; border: 1px solid #2a2a4a; border-radius: 10px; overflow: hidden; }
                    table { width: 100%; border-collapse: collapse; }
                    th { text-align: left; padding: 10px 16px; color: #a0a0b0; font-size: 0.78rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; border-bottom: 1px solid #2a2a4a; }
                    td { padding: 12px 16px; border-bottom: 1px solid #2a2a4a; font-size: 0.9rem; vertical-align: top; }
                    tr:last-child td { border-bottom: none; }
                    tr:hover td { background: rgba(88,101,242,0.05); }
                    .cmd { font-family: monospace; color: #7289fa; font-size: 0.95rem; white-space: nowrap; }
                    .desc { color: #c0c0d0; }
                    .opts { list-style: none; }
                    .opt { margin-bottom: 8px; }
                    .opt:last-child { margin-bottom: 0; }
                    .opt-name { font-family: monospace; color: #e0e0e0; font-size: 0.85rem; }
                    .badge { font-size: 0.7rem; color: #888; background: #2a2a4a; padding: 1px 6px; border-radius: 4px; margin-left: 5px; vertical-align: middle; }
                    .opt-desc { color: #a0a0b0; font-size: 0.82rem; margin-top: 2px; }
                    .choices { list-style: none; margin-top: 4px; padding-left: 10px; }
                    .choice { font-size: 0.78rem; color: #888; font-family: monospace; }
                    .choice::before { content: "• "; color: #5865F2; }
                    .none { color: #555; font-style: italic; font-size: 0.85rem; }
                </style>
            </head>
            <body>
            <nav>
                <a class="brand" href="/">TobyBot</a>
                <div class="nav-links" id="nav-menu">
                    <a href="/commands/wiki">Commands</a>
                    <a href="/intro/guilds">Intro Songs</a>
                    <a href="/oauth2/authorization/discord" class="btn-discord">Login</a>
                </div>
                <button class="nav-toggle" onclick="toggleNav()" aria-label="Toggle navigation">&#9776;</button>
            </nav>
            <div class="container">
                <h1>Commands</h1>
                <p class="subtitle">All available slash commands for TobyBot.</p>
        """.trimIndent())

        fun appendCommandCategory(title: String, commands: List<Command>) {
            if (commands.isEmpty()) return
            html.append("""<div class="category"><h2>$title</h2><div class="table-wrap"><table>""")
            html.append("""<thead><tr><th>Command</th><th>Description</th><th>Options</th></tr></thead><tbody>""")
            for (command in commands.sortedBy { it.name }) {
                html.append("<tr>")
                html.append("""<td><span class="cmd">/${command.name}</span></td>""")
                html.append("""<td class="desc">${command.description}</td>""")
                when {
                    command.optionData.isNotEmpty() -> {
                        html.append("""<td><ul class="opts">""")
                        for (option in command.optionData) {
                            html.append("""<li class="opt"><span class="opt-name">${option.name}</span><span class="badge">${option.type.name}</span><div class="opt-desc">${option.description}</div>""")
                            if (option.choices.isNotEmpty()) {
                                html.append("""<ul class="choices">""")
                                for (choice in option.choices) html.append("""<li class="choice">${choice.name}</li>""")
                                html.append("</ul>")
                            }
                            html.append("</li>")
                        }
                        html.append("</ul></td>")
                    }
                    command.subCommands.isNotEmpty() -> {
                        html.append("""<td><ul class="opts">""")
                        for (sub in command.subCommands) {
                            html.append("""<li class="opt"><span class="opt-name">${sub.name}</span><div class="opt-desc">${sub.description}</div>""")
                            if (sub.options.isNotEmpty()) {
                                html.append("""<ul class="choices">""")
                                for (option in sub.options) html.append("""<li class="choice">${option.name}: ${option.description}</li>""")
                                html.append("</ul>")
                            }
                            html.append("</li>")
                        }
                        html.append("</ul></td>")
                    }
                    else -> html.append("""<td><span class="none">&mdash;</span></td>""")
                }
                html.append("</tr>")
            }
            html.append("</tbody></table></div></div>")
        }

        appendCommandCategory("Music", commandManager.musicCommands)
        appendCommandCategory("DnD", commandManager.dndCommands)
        appendCommandCategory("Moderation", commandManager.moderationCommands)
        appendCommandCategory("Miscellaneous", commandManager.miscCommands)
        appendCommandCategory("Fetch", commandManager.fetchCommands)

        html.append("""</div><script src="/js/home.js"></script></body></html>""")
        return html.toString()
    }

    data class CommandDocumentation(
        val name: String,
        val description: String,
        val options: List<OptionDocumentation>,
        val subCommands: List<SubcommandDocumentation>
    )

    data class SubcommandDocumentation(
        val name: String,
        val description: String,
        val options: List<OptionDocumentation>
    )

    data class OptionDocumentation(
        val name: String,
        val description: String,
        val type: String,
        val choices: List<ChoiceDocumentation>? = null
    )

    data class ChoiceDocumentation(
        val name: String,
        val value: String
    )
}