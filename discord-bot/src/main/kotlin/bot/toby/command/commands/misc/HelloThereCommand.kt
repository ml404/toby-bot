package bot.toby.command.commands.misc

import core.command.CommandContext
import database.dto.UserDto
import database.service.ConfigService
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Component
class HelloThereCommand @Autowired constructor(private val configService: ConfigService) : MiscCommand {
    private val DEFAULT_DATE_FORMAT = "yyyy/MM/dd"
    private val REFERENCE_DATE = LocalDate.of(2005, 5, 19)

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guildId = event.guild?.idLong?.toString()
        val dateFormat = configService.getConfigByName("DATEFORMAT", guildId)?.value ?: DEFAULT_DATE_FORMAT
        val dateTimeFormatter = DateTimeFormatter.ofPattern(dateFormat)

        val dateArgument = event.getOption("date")?.asString

        if (dateArgument == null) {
            event.hook.sendMessage(description)
                .queue(core.command.Command.invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        runCatching {
            LocalDate.parse(dateArgument, dateTimeFormatter)
        }.onSuccess { dateGiven ->
            val responseMessage = if (dateGiven.isBefore(REFERENCE_DATE)) "Hello." else "General Kenobi."
            event.hook.sendMessage(responseMessage)
                .queue(core.command.Command.invokeDeleteOnMessageResponse(deleteDelay))
        }.onFailure { e ->
            if (e is DateTimeParseException) {
                event.hook.sendMessage(
                    "I don't recognise the format of the date you gave me, please use this format $dateFormat"
                ).queue(core.command.Command.invokeDeleteOnMessageResponse(deleteDelay))
            } else {
                event.hook.sendMessage("An unexpected error occurred.")
                    .queue(core.command.Command.invokeDeleteOnMessageResponse(deleteDelay))
            }
        }
    }

    override val name: String
        get() = "hellothere"

    override val description: String
        get() = "I have a bad understanding of time, let me know what the date is so I can greet you appropriately"

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.STRING, "date", "What is the date you would like to say hello to TobyBot for?", false)
        )
}
