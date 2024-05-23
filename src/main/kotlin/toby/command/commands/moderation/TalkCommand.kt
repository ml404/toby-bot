package toby.command.commands.moderation

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import java.util.function.Consumer

class TalkCommand : IModerationCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val member = ctx.member
        val guild = event.guild!!
        member!!.voiceState!!.channel!!.members.forEach(Consumer { target: Member? ->
            if (!member.canInteract(target!!) || !member.hasPermission(Permission.VOICE_MUTE_OTHERS) || !requestingUserDto.superUser) {
                event.hook.sendMessageFormat("You aren't allowed to unmute %s", target).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return@Consumer
            }
            val bot = guild.selfMember
            if (!bot.hasPermission(Permission.VOICE_MUTE_OTHERS)) {
                event.hook.sendMessageFormat("I'm not allowed to unmute %s", target).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return@Consumer
            }
            guild.mute(target, false).reason("Unmuted").queue()
        })
    }

    override val name: String
        get() = "talk"
    override val description: String
        get() = "Unmute everyone in your voice channel, mostly made for Among Us."
}