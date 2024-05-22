package toby.command.commands.misc

import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.ExcuseDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IExcuseService
import java.util.*

class ExcuseCommand(private val excuseService: IExcuseService) : IMiscCommand {
    private val EXCUSE_ID = "id"
    override val name = "excuse"
    private val AUTHOR = "author"
    private val ACTION = "action"
    private val stringBuilderList: MutableList<StringBuilder> = ArrayList()

    override fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx!!.event
        event.deferReply().queue()
        val guildId = event.guild!!.idLong
        if (event.options.isEmpty()) {
            lookupExcuse(event, deleteDelay)
        } else {
            val actionOptional = Optional.ofNullable(event.getOption(ACTION)).map { obj: OptionMapping -> obj.asString }
            if (actionOptional.isPresent) {
                val action = actionOptional.get()
                when (action) {
                    PENDING -> lookupPendingExcuses(event, guildId, deleteDelay)
                    ALL -> listAllExcuses(event, guildId, deleteDelay)
                    APPROVE -> {
                        val excuseIdOptional =
                            Optional.ofNullable(event.getOption(EXCUSE_ID)).map { obj: OptionMapping -> obj.asInt }
                        excuseIdOptional.ifPresent { excuseId: Int ->
                            approvePendingExcuse(
                                requestingUserDto,
                                event,
                                excuseId,
                                deleteDelay
                            )
                        }
                    }

                    DELETE -> {
                        val excuseIdOptional =
                            Optional.ofNullable(event.getOption(EXCUSE_ID)).map { obj: OptionMapping -> obj.asInt }
                        excuseIdOptional.ifPresent { excuseId: Int ->
                            deleteExcuse(
                                requestingUserDto,
                                event,
                                excuseId,
                                deleteDelay
                            )
                        }
                    }
                }
            } else {
                val memberList = Optional.ofNullable(event.getOption(AUTHOR)).map { obj: OptionMapping -> obj.mentions }
                    .map { obj: Mentions -> obj.members }.orElse(emptyList())
                val author =
                    if (memberList.isEmpty()) ctx.author!!.name else memberList.stream().findFirst().get().effectiveName
                createNewExcuse(event, author, deleteDelay)
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
        createAndAddStringBuilder()
        stringBuilderList[0].append("Listing all approved excuses below: \n")
        printOutExcuses(event, deleteDelay, excuseDtos)
    }

    private fun createAndAddStringBuilder() {
        stringBuilderList.add(StringBuilder(2000))
    }

    private fun approvePendingExcuse(
        requestingUserDto: UserDto?,
        event: SlashCommandInteractionEvent,
        pendingExcuse: Int,
        deleteDelay: Int?
    ) {
        if (requestingUserDto!!.superUser) {
            val excuseById = excuseService.getExcuseById(pendingExcuse)
            if (!excuseById?.approved!!) {
                excuseById.approved = true
                excuseService.updateExcuse(excuseById)
                event.hook.sendMessageFormat("approved excuse '%s'.", excuseById.excuse)
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            } else event.hook.sendMessage(EXISTING_EXCUSE_MESSAGE).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else sendErrorMessage(event, deleteDelay!!)
    }

    private fun lookupExcuse(event: SlashCommandInteractionEvent, deleteDelay: Int?) {
        val excuseDtos = excuseService.listApprovedGuildExcuses(event.guild!!.idLong)
        if (excuseDtos.isEmpty()) {
            event.hook.sendMessage("There are no approved excuses, consider submitting some.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        val random = Random()
        val excuseDto = excuseDtos[random.nextInt(excuseDtos.size)]
        event.hook.sendMessageFormat("Excuse #%d: '%s' - %s.", excuseDto?.id, excuseDto?.excuse, excuseDto?.author)
            .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun createNewExcuse(event: SlashCommandInteractionEvent, author: String, deleteDelay: Int?) {
        val excuseMessageOptional =
            Optional.ofNullable(event.getOption(name)).map { obj: OptionMapping -> obj.asString }
        if (excuseMessageOptional.isPresent) {
            val excuseMessage = excuseMessageOptional.get()
            val guildId = event.guild!!.idLong
            val existingExcuse = excuseService.listAllGuildExcuses(guildId).stream()
                .filter { it?.excuse == excuseMessage }.findFirst()
            if (existingExcuse.isPresent) {
                event.hook.sendMessage(EXISTING_EXCUSE_MESSAGE).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            } else {
                val excuseDto = ExcuseDto()
                excuseDto.guildId = guildId
                excuseDto.author = author
                excuseDto.excuse = excuseMessage
                val newExcuse = excuseService.createNewExcuse(excuseDto)
                event.hook.sendMessageFormat(
                    "Submitted new excuse '%s' - %s with id '%d' for approval.",
                    excuseMessage,
                    author,
                    newExcuse?.id
                ).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
        }
    }

    private fun lookupPendingExcuses(event: SlashCommandInteractionEvent, guildId: Long, deleteDelay: Int?) {
        val excuseDtos = excuseService.listPendingGuildExcuses(guildId)
        if (excuseDtos.isEmpty()) {
            event.hook.sendMessage("There are no excuses pending approval, consider submitting some.")
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        createAndAddStringBuilder()
        stringBuilderList[0].append("Listing all pending excuses below: \n")
        printOutExcuses(event, deleteDelay, excuseDtos)
    }

    private fun deleteExcuse(
        requestingUserDto: UserDto?,
        event: SlashCommandInteractionEvent,
        excuseId: Int,
        deleteDelay: Int?
    ) {
        if (requestingUserDto!!.superUser) {
            excuseService.deleteExcuseById(excuseId)
            event.hook.sendMessageFormat("deleted excuse with id '%d'.", excuseId)
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else sendErrorMessage(event, deleteDelay!!)
    }

    private fun printOutExcuses(event: SlashCommandInteractionEvent, deleteDelay: Int?, excuseDtos: List<ExcuseDto?>) {
        excuseDtos.forEach {
            var sb = stringBuilderList[stringBuilderList.size - 1]
            val excuseString = String.format("Excuse #%d: '%s' - %s. \n", it?.id, it?.excuse, it?.author)
            if (sb.length + excuseString.length >= 2000) {
                createAndAddStringBuilder()
                sb = stringBuilderList[stringBuilderList.size - 1]
            }
            sb.append(excuseString)
        }
        stringBuilderList.forEach {
            event.hook.sendMessage(it.toString()).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
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
                "Use in combination with a approve action for pending IDs, or delete action for an approved ID"
            ),
            OptionData(OptionType.STRING, name, "Use in combination with an approve being true. Max 200 characters"),
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
    }
}