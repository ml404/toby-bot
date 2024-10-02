package toby.jpa.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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

        htmlBuilder.append("<html><head><title>Command Documentation</title></head><body>")
        htmlBuilder.append("<h1>Command Documentation</h1>")

        val commands = commandManager.allCommands
        for (command in commands) {
            htmlBuilder.append("<h2>${command.name}</h2>")
            htmlBuilder.append("<p>${command.description}</p>")

            if (command.optionData.isNotEmpty()) {
                htmlBuilder.append("<h3>Options:</h3><ul>")
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
                htmlBuilder.append("</ul>")
            }
        }

        htmlBuilder.append("</body></html>")
        return htmlBuilder.toString()
    }
}