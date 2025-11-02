package bot.toby.command.commands.moderation

import bot.toby.helpers.VoiceStateHelper.muteOrUnmuteMembers
import core.command.CommandContext
import database.dto.UserDto
import org.springframework.stereotype.Component

@Component
class TalkCommand : ModerationCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()
        val member = ctx.member
        val guild = event.guild!!
        muteOrUnmuteMembers(member, requestingUserDto, event, deleteDelay, guild, false)
    }

        override val name: String
        get() = "talk"
    override val description: String
        get() = "Unmute everyone in your voice channel, mostly made for Among Us."
}