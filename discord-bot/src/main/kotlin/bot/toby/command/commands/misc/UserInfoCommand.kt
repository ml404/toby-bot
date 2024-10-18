package bot.toby.command.commands.misc

import core.command.CommandContext
import database.service.IUserService
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class UserInfoCommand @Autowired constructor(private val userService: IUserService) : MiscCommand {
    private val USERS = "users"
    override fun handle(ctx: CommandContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply(true).queue()
        printUserInfo(event, requestingUserDto, deleteDelay)
    }

    private fun printUserInfo(
        event: SlashCommandInteractionEvent,
        requestingUserDto: database.dto.UserDto,
        deleteDelay: Int?
    ) {
        if (event.options.isEmpty()) {
            event.member?.listUserInfoForMember(event, deleteDelay)
        } else {
            if (requestingUserDto.superUser) {
                val memberList = event.getOption(USERS)?.mentions?.members ?: emptyList()
                memberList.forEach { member -> member.listUserInfoForMember(event, deleteDelay) }
            } else {
                event.hook.sendMessage("You do not have permission to view user permissions, if this is a mistake talk to the server owner")
                    .setEphemeral(true).queue(
                        core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelay!!)
                    )
            }
        }
    }

    private fun Member.listUserInfoForMember(
        event: SlashCommandInteractionEvent,
        deleteDelay: Int?
    ) {
        logger.info { " Doing lookup on user for guildId '${this.guild.idLong}' and discordId '${this.idLong}' " }
        val userSearched = userService.getUserById(this.idLong, this.guild.idLong)
        val userInfoMessage = userSearched?.let {
            logger.info { " Found user '${it}' from lookup " }
            val introMessage = calculateMusicFileData(event.member!!, userSearched)
            "Here are the permissions for '${this.effectiveName}': '${userSearched.getPermissionsAsString()}'. \n $introMessage"
        } ?: "I was unable to retrieve information for '${this.effectiveName}'."
        event.hook
            .sendMessage(userInfoMessage)
            .setEphemeral(true)
            .queue(core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun calculateMusicFileData(member: Member, requestingUserDto: database.dto.UserDto): String {
        val musicFiles = requestingUserDto.musicDtos.filter { !it.fileName.isNullOrBlank() }.sortedBy { it.index }
        return if (musicFiles.isEmpty()) {
            "There is no valid intro music file associated with user ${member.effectiveName}."
        } else {
            val fileDescriptions = musicFiles.mapIndexed { index, musicDto ->
                "${index + 1}. '${musicDto.fileName}' (Volume: ${musicDto.introVolume})"
            }.joinToString(separator = "\n")

            "Your intro songs are currently set as:\n$fileDescriptions"
        }
    }

    override val name: String = "userinfo"
    override val description: String =
        "Let me tell you about the permissions tied to the user mentioned (no mention is your own)."
    override val optionData: List<OptionData> =
        listOf(OptionData(OptionType.STRING, USERS, "List of users to print info about"))
}
