package web.controller

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

    // The HTML wiki at /commands/wiki used to live here as a hand-rolled
    // StringBuilder with its own <nav>, drifting away from the shared navbar
    // fragment used by every other page. It now lives in
    // [CommandWikiController] and renders via the templates/commands.html
    // Thymeleaf template, reusing fragments/navbar.html for consistency.

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
