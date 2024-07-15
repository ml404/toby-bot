package toby.command.commands.misc

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService
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
            val introMessage = requestingUserDto.musicDto?.let {
                if (it.fileName.isNullOrBlank()) "There is no intro music file associated with your user."
                else "Your intro song is currently set as: '${it.fileName}'."
            } ?: "I was unable to retrieve your music file."
            event.hook.sendMessage(introMessage).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else {
            if (requestingUserDto.superUser) {
                val memberList = event.getOption(USERS)?.mentions?.members ?: emptyList()
                memberList.forEach { member ->
                    val mentionedUser = userService.getUserById(member.idLong, member.guild.idLong)
                    val userInfoMessage = mentionedUser?.let {
                        val introMessage = it.musicDto?.let { musicDto ->
                            if (musicDto.fileName.isNullOrBlank()) "There is no intro music file associated with '${member.effectiveName}'."
                            else "Their intro song is currently set as: '${musicDto.fileName}'."
                        } ?: "I was unable to retrieve an associated music file for '${member.effectiveName}'."

                        "Here are the permissions for '${member.effectiveName}': '$mentionedUser'. $introMessage"
                    } ?: "I was unable to retrieve information for '${member.effectiveName}'."
                    event.hook.sendMessage(userInfoMessage).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                }
            } else {
                event.hook.sendMessage("You do not have permission to view user permissions, if this is a mistake talk to the server owner").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!)
                )
            }
        }
    }

    override val name: String = "userinfo"
    override val description: String =
        "Let me tell you about the permissions tied to the user mentioned (no mention is your own)."
    override val optionData: List<OptionData> =
        listOf(OptionData(OptionType.STRING, USERS, "List of users to print info about"))
}
