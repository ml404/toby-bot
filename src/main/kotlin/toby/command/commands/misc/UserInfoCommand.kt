package toby.command.commands.misc

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService
import java.util.*
import java.util.function.Consumer

class UserInfoCommand(private val userService: IUserService) : IMiscCommand {
    private val USERS = "users"
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        printUserInfo(event, requestingUserDto, deleteDelay)
    }

    private fun printUserInfo(event: SlashCommandInteractionEvent, requestingUserDto: UserDto, deleteDelay: Int?) {
        if (event.options.isEmpty()) {
            event.hook.sendMessageFormat("Here are your permissions: '%s'.", requestingUserDto).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            val musicDto = requestingUserDto.musicDto
            if (musicDto != null) {
                if (musicDto.fileName == null || musicDto.fileName!!.isBlank()) {
                    event.hook.sendMessage("There is no intro music file associated with your user.").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
                } else if (musicDto.fileName != null) {
                    event.hook.sendMessageFormat("Your intro song is currently set as: '%s'.", musicDto.fileName).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
                }
            } else event.hook.sendMessage("I was unable to retrieve your music file.").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
        } else {
            if (requestingUserDto.superUser) {
                val memberList = Optional.ofNullable(event.getOption(USERS)).map { obj: OptionMapping -> obj.mentions }.map { obj: Mentions -> obj.members }.orElse(emptyList())
                memberList.forEach(Consumer { member: Member ->
                    val mentionedUser = userService.getUserById(member.idLong, member.guild.idLong)
                    event.hook.sendMessageFormat("Here are the permissions for '%s': '%s'.", member.effectiveName, mentionedUser).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                    val musicDto = mentionedUser?.musicDto
                    if (musicDto != null) {
                        if (musicDto.fileName == null || musicDto.fileName!!.isBlank()) {
                            event.hook.sendMessageFormat("There is no intro music file associated with '%s'.", member.effectiveName).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
                        } else if (musicDto.fileName != null) {
                            event.hook.sendMessageFormat("Their intro song is currently set as: '%s'.", musicDto.fileName).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
                        }
                    } else event.hook.sendMessageFormat("I was unable to retrieve an associated music file for '%s'.", member.effectiveName).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
                })
            } else event.hook.sendMessage("You do not have permission to view user permissions, if this is a mistake talk to the server owner").setEphemeral(true).queue()
        }
    }

    override val name: String
        get() = "userinfo"
    override val description: String
        get() = "Let me tell you about the permissions tied to the user mentioned (no mention is your own)."
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.STRING, USERS, "List of users to print info about"))
}