package toby.command.commands.moderation

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import java.util.function.Consumer

class ShhCommand : IModerationCommand {
    override fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx!!.event
        event.deferReply().queue()
        val member = ctx.member
        val guild = event.guild!!
        member!!.voiceState!!.channel!!.members.forEach(Consumer<Member> { target: Member? ->
            if (!member.canInteract(target!!) || !member.hasPermission(Permission.VOICE_MUTE_OTHERS) || !requestingUserDto.superUser) {
                event.hook.sendMessageFormat("You aren't allowed to mute %s", target).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return@Consumer
            }
            val bot = guild.selfMember
            if (!bot.hasPermission(Permission.VOICE_MUTE_OTHERS)) {
                event.hook.sendMessageFormat("I'm not allowed to mute %s", target).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return@Consumer
            }
            guild.mute(target, true)
                    .reason("Muted")
                    .queue()
        })
    }

    override val name: String
        get() = "shh"
    override val description: String
        get() = """
            Silence everyone in your voice channel, please only use for Among Us.
            ${String.format("Usage: `%sshh`", "/")}
            """.trimIndent()
}