package bot.toby.command.commands.moderation

import bot.toby.helpers.UserDtoHelper
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.service.UserService
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class AdjustUserCommand @Autowired constructor(
    private val userService: UserService,
    private val userDtoHelper: UserDtoHelper
) : ModerationCommand {
    private val PERMISSION_NAME = "name"
    private val USERS = "users"

    override fun handle(ctx: CommandContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply(true).queue()
        val member = ctx.member
        val guild = event.guild!!
        val adjustedDeleteDelay = deleteDelay ?: 0

        val mentionedMembers = channelAndArgumentValidation(event, requestingUserDto, guild.owner, member, adjustedDeleteDelay) ?: return

        mentionedMembers.forEach { targetMember ->
            val targetUserDto = userService.getUserById(targetMember.idLong, targetMember.guild.idLong)
            if (targetUserDto != null) {
                updateUserPermissions(requestingUserDto, targetUserDto, member, event, adjustedDeleteDelay, targetMember)
            } else {
                createNewUser(event, targetMember, adjustedDeleteDelay)
            }
        }
    }

    private fun updateUserPermissions(
        requestingUserDto: database.dto.UserDto,
        targetUserDto: database.dto.UserDto,
        member: Member?,
        event: SlashCommandInteractionEvent,
        deleteDelay: Int,
        targetMember: Member
    ) {
        val isSameGuild = requestingUserDto.guildId == targetUserDto.guildId
        val requesterCanAdjustPermissions = userDtoHelper.userAdjustmentValidation(requestingUserDto, targetUserDto) || member!!.isOwner

        if (requesterCanAdjustPermissions && isSameGuild) {
            validateArgumentsAndUpdateUser(event, targetUserDto, member!!.isOwner, deleteDelay)
            event.hook.sendMessageFormat("Updated user %s's permissions", targetMember.effectiveName)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
        } else {
            event.hook.sendMessageFormat(
                "User '%s' is not allowed to adjust the permissions of user '%s'.",
                member!!.effectiveName,
                targetMember.effectiveName
            ).queue(invokeDeleteOnMessageResponse(deleteDelay))
        }
    }

    private fun validateArgumentsAndUpdateUser(
        event: SlashCommandInteractionEvent,
        targetUserDto: database.dto.UserDto,
        isOwner: Boolean,
        deleteDelay: Int
    ) {
        val permission = event.getOption(PERMISSION_NAME)?.asString
        val modifier = event.getOption(MODIFIER)?.asInt

        if (permission == null && modifier == null) {
            event.hook.sendMessage("You did not mention a valid permission to update")
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }

        modifier?.let { targetUserDto.initiativeModifier = it }

        permission?.uppercase()?.let {
            when (database.dto.UserDto.Permissions.valueOf(it)) {
                database.dto.UserDto.Permissions.MUSIC -> targetUserDto.musicPermission =
                    !targetUserDto.musicPermission

                database.dto.UserDto.Permissions.DIG -> targetUserDto.digPermission = !targetUserDto.digPermission
                database.dto.UserDto.Permissions.MEME -> targetUserDto.memePermission =
                    !targetUserDto.memePermission

                database.dto.UserDto.Permissions.SUPERUSER -> {
                    if (isOwner) targetUserDto.superUser = !targetUserDto.superUser
                }
            }
        }

        userService.updateUser(targetUserDto)
    }

    private fun createNewUser(event: SlashCommandInteractionEvent, targetMember: Member, deleteDelay: Int) {
        val newDto = database.dto.UserDto(targetMember.idLong, targetMember.guild.idLong)
        userService.createNewUser(newDto)
        event.hook.sendMessageFormat(
            "User %s's permissions did not exist in this server's database, they have now been created",
            targetMember.effectiveName
        ).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun channelAndArgumentValidation(
        event: SlashCommandInteractionEvent,
        requestingUserDto: database.dto.UserDto?,
        member: Member?,
        guildOwner: Member?,
        deleteDelay: Int
    ): List<Member>? {
        if (!member!!.isOwner && requestingUserDto?.superUser != true) {
            event.hook.sendMessage(getErrorMessage(guildOwner!!.effectiveName))
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return null
        }

        val mentionedMembers = event.getOption(USERS)?.mentions?.members
        if (mentionedMembers.isNullOrEmpty()) {
            event.hook.sendMessage("You must mention 1 or more Users to adjust permissions of")
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return null
        }

        if (event.getOption(PERMISSION_NAME)?.asString == null) {
            event.hook.sendMessage("You must mention a permission to adjust for the user you've mentioned.")
                .setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return null
        }

        return mentionedMembers
    }

    override val name: String
        get() = "adjustuser"
    override val description: String
        get() = "Use this command to adjust the mentioned user's permissions to use TobyBot commands for your server"
    override val optionData: List<OptionData>
        get() {
            val userOption = OptionData(OptionType.STRING, USERS, "User(s) who you would like to adjust the permissions of.", true)
            val permission = OptionData(OptionType.STRING, PERMISSION_NAME, "What permission to adjust for the user", true).apply {
                addChoice(
                    database.dto.UserDto.Permissions.MUSIC.name,
                    database.dto.UserDto.Permissions.MUSIC.name
                )
                addChoice(
                    database.dto.UserDto.Permissions.MEME.name,
                    database.dto.UserDto.Permissions.MEME.name
                )
                addChoice(database.dto.UserDto.Permissions.DIG.name, database.dto.UserDto.Permissions.DIG.name)
                addChoice(
                    database.dto.UserDto.Permissions.SUPERUSER.name,
                    database.dto.UserDto.Permissions.SUPERUSER.name
                )
            }
            val initiative = OptionData(OptionType.INTEGER, MODIFIER, "modifier for the initiative command when used on your user")
            return listOf(userOption, permission, initiative)
        }

    companion object {
        const val MODIFIER = "modifier"
    }
}
