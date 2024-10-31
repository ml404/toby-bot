package bot.toby.command.commands.misc

import bot.toby.helpers.UserDtoHelper
import bot.toby.helpers.UserDtoHelper.Companion.produceMusicFileDataStringForPrinting
import core.command.CommandContext
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class UserInfoCommand @Autowired constructor(private val userDtoHelper: UserDtoHelper) : MiscCommand {
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
        val userSearched = userDtoHelper.calculateUserDto(this.idLong, this.guild.idLong)
        val userInfoMessage = userSearched.let {
            logger.info { " Found user '${it}' from lookup " }
            val introMessage = produceMusicFileDataStringForPrinting(event.member!!, userSearched)
            "Here are the permissions for '${this.effectiveName}': '${userSearched.getPermissionsAsString()}'. \n $introMessage"
        }
        event.hook
            .sendMessage(userInfoMessage)
            .setEphemeral(true)
            .queue(core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    override val name: String = "userinfo"
    override val description: String =
        "Let me tell you about the permissions tied to the user mentioned (no mention is your own)."
    override val optionData: List<OptionData> =
        listOf(OptionData(OptionType.STRING, USERS, "List of users to print info about"))
}
