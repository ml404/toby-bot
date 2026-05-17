package bot.toby.command.commands.misc

import bot.toby.helpers.UserDtoHelper
import bot.toby.helpers.UserDtoHelper.Companion.produceMusicFileDataStringForPrinting
import common.leveling.LevelCurve
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class UserInfoCommand @Autowired constructor(private val userDtoHelper: UserDtoHelper) : MiscCommand {
    companion object {
        private const val USERS = "users"
    }
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()
        printUserInfo(event, requestingUserDto, deleteDelay)
    }

    private fun printUserInfo(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto,
        deleteDelay: Int
    ) {
        if (event.options.isEmpty()) {
            event.member?.listUserInfoForMember(event, deleteDelay)
        } else {
            if (requestingUserDto.superUser) {
                val memberList = event.getOption(USERS)?.mentions?.members ?: emptyList()
                memberList.forEach { member -> member.listUserInfoForMember(event, deleteDelay) }
            } else {
                event.hook.replyEphemeralAndDelete(
                    "You do not have permission to view user permissions, if this is a mistake talk to the server owner",
                    deleteDelay,
                )
            }
        }
    }

    private fun Member.listUserInfoForMember(
        event: SlashCommandInteractionEvent,
        deleteDelay: Int
    ) {
        logger.info { " Doing lookup on user for guildId '${this.guild.idLong}' and discordId '${this.idLong}' " }
        val userSearched = userDtoHelper.calculateUserDto(this.idLong, this.guild.idLong)
        val userInfoMessage = userSearched.let {
            logger.info { " Found user '$it' from lookup " }
            val introMessage = produceMusicFileDataStringForPrinting(event.member!!, userSearched)
            val progress = LevelCurve.progress(userSearched.xp)
            val levelLine = "Level ${progress.level} (${progress.xpIntoLevel}/${progress.xpForNextLevel} XP, total ${userSearched.xp})"
            "Here are the permissions for '${this.effectiveName}': '${userSearched.getPermissionsAsString()}'. \n $levelLine \n $introMessage"
        }
        event.hook.replyEphemeralAndDelete(userInfoMessage, deleteDelay)
    }

    override val name: String = "userinfo"
    override val description: String =
        "Let me tell you about the permissions tied to the user mentioned (no mention is your own)."
    override val optionData: List<OptionData> =
        listOf(OptionData(OptionType.STRING, USERS, "List of users to print info about"))
}
