package toby.jpa.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import toby.command.ICommand
import toby.documentation.ChoiceDocumentation
import toby.documentation.CommandDocumentation
import toby.documentation.OptionDocumentation
import toby.managers.CommandManager

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
        return commandManager.allCommands.map { command ->
            CommandDocumentation(
                name = command.name,
                description = command.description,
                options = command.optionData.map { option ->
                    OptionDocumentation(
                        name = option.name,
                        description = option.description,
                        type = option.type.name,
                        choices = option.choices.map { choice ->
                            ChoiceDocumentation(
                                name = choice.name,
                                value = choice.asString
                            )
                        }
                    )
                }
            )
        }
    }

    @Operation(summary = "Get command wiki", description = "Fetch an HTML representation of all available commands.")
    @GetMapping("/wiki", produces = ["text/html"])
    fun getCommandsWiki(): String {
        val htmlBuilder = StringBuilder()

        // Add the basic structure
        htmlBuilder.append("<h1>Command Documentation</h1>")

        // Function to append a category of commands
        fun appendCommandCategory(title: String, commands: List<ICommand>) {
            htmlBuilder.append("<h2>$title</h2>")
            htmlBuilder.append("<table border='1'><thead><tr><th>Command</th><th>Description</th><th>Options</th></tr></thead><tbody>")
            for (command in commands.sortedBy { it.name }) {
                htmlBuilder.append("<tr>")
                htmlBuilder.append("<td><strong>/${command.name}</strong></td>")
                htmlBuilder.append("<td>${command.description}</td>")

                // Add options
                if (command.optionData.isNotEmpty()) {
                    htmlBuilder.append("<td><ul>")
                    for (option in command.optionData) {
                        htmlBuilder.append("<li><strong>${option.name}</strong>: ${option.description} (Type: ${option.type.name})</li>")
                        if (option.choices.isNotEmpty()) {
                            htmlBuilder.append("<ul>")
                            for (choice in option.choices) {
                                htmlBuilder.append("<li>${choice.name}: ${choice.asString}</li>")
                            }
                            htmlBuilder.append("</ul>")
                        }
                    }
                    htmlBuilder.append("</ul></td>")
                } else {
                    htmlBuilder.append("<td>No options</td>")
                }
                htmlBuilder.append("</tr>")
            }
            htmlBuilder.append("</tbody></table>")
        }

        // Append each subcategory of commands
        appendCommandCategory("Music Commands", commandManager.musicCommands)
        appendCommandCategory("Moderation Commands", commandManager.moderationCommands)
        appendCommandCategory("Miscellaneous Commands", commandManager.miscCommands)
        appendCommandCategory("Fetch Commands", commandManager.fetchCommands)

        htmlBuilder.append("</body></html>")
        return htmlBuilder.toString()
    }
}