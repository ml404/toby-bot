package bot.toby.command.commands.moderation

import bot.toby.command.CommandContext
import bot.toby.helpers.VoiceStateHelper.muteOrUnmuteMembers
import org.springframework.stereotype.Component

@Component
class TalkCommand : IModerationCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
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