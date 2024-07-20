package toby.command.commands.moderation

import toby.command.CommandContext
import toby.helpers.VoiceStateHelper.muteOrUnmuteMembers
import toby.jpa.dto.UserDto

class TalkCommand : IModerationCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
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