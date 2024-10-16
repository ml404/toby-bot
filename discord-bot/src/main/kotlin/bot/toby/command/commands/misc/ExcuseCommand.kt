package bot.toby.command.commands.misc

import bot.database.dto.ExcuseDto
import bot.database.service.IExcuseService
import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class ExcuseCommand @Autowired constructor(private val excuseService: IExcuseService) : IMiscCommand {
    private val EXCUSE = "excuse"
    private val EXCUSE_ID = "id"
    private val AUTHOR = "author"
    private val ACTION = "action"

    override val name = "excuse"

    override fun handle(ctx: CommandContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val guildId = event.guild!!.idLong
        when {
            event.options.isEmpty() -> lookupExcuse(event, deleteDelay)
            else -> {
                val action = event.getOption(ACTION)?.asString
                when (action) {
                    PENDING -> lookupPendingExcuses(event, guildId, deleteDelay)
                    ALL -> listAllExcuses(event, guildId, deleteDelay)
                    APPROVE -> approveExcuse(requestingUserDto, event, deleteDelay)
                    DELETE -> deleteExcuse(requestingUserDto, event, deleteDelay)
                    else -> createNewExcuse(event, deleteDelay)
                }
            }
        }
    }

    private fun listAllExcuses(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int?) {
        val excuseDtos = excuseService.listApprovedGuildExcuses(guildId)
        if (excuseDtos.isEmpty()) {
            event.hook.sendMessage("There are no approved excuses, consider submitting some.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        val excusesMessage = buildExcusesMessage(excuseDtos)
        event.hook.sendMessage(excusesMessage).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun buildExcusesMessage(excuseDtos: List<ExcuseDto?>): String {
        val builder = StringBuilder("Listing all approved excuses below: \n")
        excuseDtos.forEachIndexed { index, excuse ->
            builder.append("Excuse #${excuse?.id}: '${excuse?.excuse}' - ${excuse?.author}. \n")
            if (index == MAX_EXCUSES_DISPLAYED) return@forEachIndexed
        }
        return builder.toString()
    }

    private fun approveExcuse(
        requestingUserDto: bot.database.dto.UserDto?,
        event: SlashCommandInteractionEvent,
        deleteDelay: Int?
    ) {
        if (requestingUserDto?.superUser == true) {
            val excuseId = event.getOption(EXCUSE_ID)?.asLong ?: return
            val excuseById = excuseService.getExcuseById(excuseId) ?: return
            if (!excuseById.approved) {
                excuseById.approved = true
                excuseService.updateExcuse(excuseById)
                event.hook.sendMessageFormat("Approved excuse '%s'.", excuseById.excuse)
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            } else {
                event.hook.sendMessage(EXISTING_EXCUSE_MESSAGE).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
        } else {
            sendErrorMessage(event, deleteDelay!!)
        }
    }

    private fun lookupExcuse(event: SlashCommandInteractionEvent, deleteDelay: Int?) {
        val excuseDtos = excuseService.listApprovedGuildExcuses(event.guild!!.idLong)
        if (excuseDtos.isEmpty()) {
            event.hook.sendMessage("There are no approved excuses, consider submitting some.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        val randomExcuse = excuseDtos.random()
        event.hook.sendMessageFormat(
            "Excuse #%d: '%s' - %s.",
            randomExcuse?.id,
            randomExcuse?.excuse,
            randomExcuse?.author
        )
            .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun createNewExcuse(event: SlashCommandInteractionEvent, deleteDelay: Int?) {
        val excuseMessage = event.getOption(EXCUSE)?.asString ?: return
        val guildId = event.guild!!.idLong
        val author = event.getOption(AUTHOR)?.asMember?.effectiveName ?: event.user.name
        val existingExcuse = excuseService.listAllGuildExcuses(guildId).find { it?.excuse == excuseMessage }
        if (existingExcuse != null) {
            event.hook.sendMessage(EXISTING_EXCUSE_MESSAGE).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else {
            val excuseDto = ExcuseDto().apply {
                this.guildId = guildId
                this.author = author
                this.excuse = excuseMessage
            }

            val newExcuse = excuseService.createNewExcuse(excuseDto)
            event.hook.sendMessageFormat(
                "Submitted new excuse '%s' - %s with id '%d' for approval.",
                excuseMessage,
                author,
                newExcuse?.id
            ).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    private fun lookupPendingExcuses(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int?) {
        val excuseDtos = excuseService.listPendingGuildExcuses(guildId)
        if (excuseDtos.isEmpty()) {
            event.hook.sendMessage("There are no excuses pending approval, consider submitting some.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        val excusesMessage = buildExcusesMessage(excuseDtos)
        event.hook.sendMessage(excusesMessage).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun deleteExcuse(
        requestingUserDto: bot.database.dto.UserDto?,
        event: SlashCommandInteractionEvent,
        deleteDelay: Int?
    ) {
        if (requestingUserDto?.superUser == true) {
            val excuseId = event.getOption(EXCUSE_ID)?.asLong ?: return
            excuseService.deleteExcuseById(excuseId)
            event.hook.sendMessageFormat("Deleted excuse with id '%d'.", excuseId)
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else {
            sendErrorMessage(event, deleteDelay!!)
        }
    }

    override val description: String
        get() = "Let me give you a convenient excuse!"

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.STRING, ACTION, "Which action would you like to take?")
                .addChoice(PENDING, PENDING)
                .addChoice(ALL, ALL)
                .addChoice(APPROVE, APPROVE)
                .addChoice(DELETE, DELETE),
            OptionData(
                OptionType.INTEGER,
                EXCUSE_ID,
                "Use in combination with an approve action for pending IDs, or delete action for an approved ID"
            ),
            OptionData(OptionType.STRING, EXCUSE, "Use in combination with an approve being true. Max 200 characters"),
            OptionData(
                OptionType.USER,
                AUTHOR,
                "Who made this excuse? Leave blank to attribute to the user who called the command"
            )
        )

    companion object {
        const val PENDING = "pending"
        const val ALL = "all"
        const val APPROVE = "approve"
        const val DELETE = "delete"
        const val EXISTING_EXCUSE_MESSAGE = "I've heard that one before, keep up."
        const val MAX_EXCUSES_DISPLAYED = 5
    }
}

