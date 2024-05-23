package toby.command.commands.misc

import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*

class HelloThereCommand(private val configService: IConfigService) : IMiscCommand {
    private val DATE = "date"
    private var DATE_FORMAT: String? = null
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val args = ctx.event.options
        DATE_FORMAT = "DATEFORMAT"
        val dateformat = configService.getConfigByName(DATE_FORMAT, event.guild?.idLong?.toString())?.value
        val dateTimeFormatter = dateformat?.let { DateTimeFormatter.ofPattern(it) }
        val date = dateTimeFormatter?.let { LocalDate.parse("2005/05/19", it) }
        if (args.isEmpty()) {
            event.hook.sendMessage(description).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else try {
            val dateGiven =
                dateTimeFormatter?.let {
                    LocalDate.parse(Optional.ofNullable(event.getOption(DATE)).map { obj: OptionMapping -> obj.asString }.orElse(LocalDate.now().toString()),
                        it
                    )
                }
            if (dateGiven?.isBefore(date)!!) {
                event.hook.sendMessage("Hello.").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            } else {
                event.hook.sendMessage("General Kenobi.").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
        } catch (e: DateTimeParseException) {
            event.hook.sendMessageFormat("I don't recognise the format of the date you gave me, please use this format %s", dateformat).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    override val name: String
        get() = "hellothere"
    override val description: String
        get() = "I have a bad understanding of time, let me know what the date is so I can greet you appropriately"
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.STRING, "date", "What is the date you would like to say hello to TobyBot for?", true))
}
