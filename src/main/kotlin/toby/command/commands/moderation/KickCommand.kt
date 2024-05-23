package toby.command.commands.moderation

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import java.util.*
import java.util.function.Consumer

class KickCommand : IModerationCommand {
    private val USERS = "users"
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val guild = event.guild!!
        val member = ctx.member
        val botMember = guild.selfMember
        val optionalMemberList = Optional.ofNullable(event.getOption(USERS)).map { obj: OptionMapping -> obj.mentions }.map { obj: Mentions -> obj.members }
        if (optionalMemberList.isEmpty) {
            event.hook.sendMessage("You must mention 1 or more Users to shoot").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        optionalMemberList.get().forEach(Consumer { target: Member? ->
            if (!member!!.canInteract(target!!) || !member.hasPermission(Permission.KICK_MEMBERS)) {
                event.hook.sendMessageFormat("You can't kick %s", target).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return@Consumer
            }
            if (!botMember.canInteract(target) || !botMember.hasPermission(Permission.KICK_MEMBERS)) {
                event.hook.sendMessageFormat("I'm not allowed to kick %s", target).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return@Consumer
            }
            guild.kick(target).reason("because you told me to.").queue(
                    { event.hook.sendMessage("Shot hit the mark... something about fortnite?").queue(invokeDeleteOnMessageResponse(deleteDelay!!)) }
            ) { error: Throwable -> event.hook.sendMessageFormat("Could not kick %s", error.message).queue(invokeDeleteOnMessageResponse(deleteDelay!!)) }
        })
    }

    override val name: String
        get() = "kick"
    override val description: String
        get() = "Kick a member off the server."
    override val optionData: List<OptionData>
        get() = listOf(OptionData(OptionType.STRING, USERS, "User(s) to kick", true))
}