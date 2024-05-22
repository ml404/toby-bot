package toby.command.commands.moderation

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.helpers.UserDtoHelper
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService
import java.util.*
import java.util.function.Consumer

class AdjustUserCommand(private val userService: IUserService) : IModerationCommand {
    private val PERMISSION_NAME = "name"
    private val USERS = "users"
    override fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx!!.event
        event.deferReply().queue()
        val member = ctx.member
        val guild = event.guild!!
        val mentionedMembers =
            channelAndArgumentValidation(event, requestingUserDto, guild.owner, member, deleteDelay!!)
                ?: return
        mentionedMembers.forEach(Consumer { targetMember: Member ->
            val targetUserDto = userService.getUserById(targetMember.idLong, targetMember.guild.idLong)
            //Check to see if the database contained an entry for the user we have made a request against
            if (targetUserDto != null) {
                val isSameGuild = requestingUserDto.guildId == targetUserDto.guildId
                val requesterCanAdjustPermissions =
                    UserDtoHelper.userAdjustmentValidation(requestingUserDto, targetUserDto) || member!!.isOwner
                if (requesterCanAdjustPermissions && isSameGuild) {
                    validateArgumentsAndUpdateUser(event, targetUserDto, member!!.isOwner, deleteDelay)
                    event.hook.sendMessageFormat("Updated user %s's permissions", targetMember.effectiveName)
                        .queue(invokeDeleteOnMessageResponse(deleteDelay))
                } else event.hook.sendMessageFormat(
                    "User '%s' is not allowed to adjust the permissions of user '%s'.",
                    member!!.effectiveName,
                    targetMember.effectiveName
                ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            } else {
                createNewUser(event, targetMember, deleteDelay)
            }
        })
    }

    private fun validateArgumentsAndUpdateUser(
        event: SlashCommandInteractionEvent,
        targetUserDto: UserDto,
        isOwner: Boolean,
        deleteDelay: Int
    ) {
        val permissionOptional =
            Optional.ofNullable(event.getOption(PERMISSION_NAME)).map { obj: OptionMapping -> obj.asString }
        val modifierOptional = Optional.ofNullable(event.getOption(MODIFIER)).map { obj: OptionMapping -> obj.asInt }
        if (permissionOptional.isEmpty && modifierOptional.isEmpty) {
            event.hook.sendMessage("You did not mention a valid permission to update").setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return
        }
        modifierOptional.ifPresent { modifier: Int? -> targetUserDto.initiativeModifier = modifier!! }
        when (UserDto.Permissions.valueOf(permissionOptional.get().uppercase(Locale.getDefault()))) {
            UserDto.Permissions.MUSIC -> targetUserDto.musicPermission = !targetUserDto.musicPermission
            UserDto.Permissions.DIG -> targetUserDto.digPermission = !targetUserDto.digPermission
            UserDto.Permissions.MEME -> targetUserDto.memePermission = !targetUserDto.memePermission
            UserDto.Permissions.SUPERUSER -> {
                if (isOwner) targetUserDto.superUser = !targetUserDto.superUser
            }
        }
        userService.updateUser(targetUserDto)
    }

    private fun createNewUser(event: SlashCommandInteractionEvent, targetMember: Member, deleteDelay: Int) {
        //Database did not contain an entry for the user we have made a request against, so make one.
        val newDto = UserDto()
        newDto.discordId = targetMember.idLong
        newDto.guildId = targetMember.guild.idLong
        userService.createNewUser(newDto)
        event.hook.sendMessageFormat(
            "User %s's permissions did not exist in this server's database, they have now been created",
            targetMember.effectiveName
        ).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun channelAndArgumentValidation(
        event: SlashCommandInteractionEvent,
        requestingUserDto: UserDto?,
        member: Member?,
        guildOwner: Member?,
        deleteDelay: Int
    ): List<Member>? {
        if (!member!!.isOwner && !requestingUserDto!!.superUser) {
            event.hook.sendMessage(getErrorMessage(guildOwner!!.effectiveName)!!).setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return null
        }
        val mentionedMembersOptional =
            Optional.ofNullable(event.getOption(USERS)).map { obj: OptionMapping -> obj.mentions }
                .map { obj: Mentions -> obj.members }
        if (mentionedMembersOptional.isEmpty) {
            event.hook.sendMessage("You must mention 1 or more Users to adjust permissions of").setEphemeral(true)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return null
        }
        if (Optional.ofNullable(event.getOption(PERMISSION_NAME)).map { obj: OptionMapping -> obj.asString }.isEmpty) {
            event.hook.sendMessage("You must mention a permission to adjust of the user you've mentioned.")
                .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return null
        }
        return mentionedMembersOptional.get()
    }

    override val name: String
        get() = "adjustuser"
    override val description: String
        get() = "Use this command to adjust the mentioned user's permissions to use TobyBot commands for your server"
    override val optionData: List<OptionData>
        get() {
            val userOption =
                OptionData(OptionType.STRING, USERS, "User(s) who you would like to adjust the permissions of.", true)
            val permission =
                OptionData(OptionType.STRING, PERMISSION_NAME, "What permission to adjust for the user", true)
            permission.addChoice(UserDto.Permissions.MUSIC.name, UserDto.Permissions.MUSIC.name)
            permission.addChoice(UserDto.Permissions.MEME.name, UserDto.Permissions.MEME.name)
            permission.addChoice(UserDto.Permissions.DIG.name, UserDto.Permissions.DIG.name)
            permission.addChoice(UserDto.Permissions.SUPERUSER.name, UserDto.Permissions.SUPERUSER.name)
            val initiative =
                OptionData(OptionType.INTEGER, MODIFIER, "modifier for the initiative command when used on your user")
            return listOf(userOption, permission, initiative)
        }

    companion object {
        const val MODIFIER = "modifier"
    }
}
