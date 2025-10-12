package bot.toby.command.commands.moderation

import bot.toby.helpers.VoiceStateHelper.muteOrUnmuteMembers
import core.command.CommandContext
import database.dto.UserDto
import org.springframework.stereotype.Component

@Component
class ShhCommand : ModerationCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()
        val member = ctx.member
        val guild = event.guild!!
        muteOrUnmuteMembers(member, requestingUserDto, event, deleteDelay, guild, true)
    }

    override val name: String
        get() = "shh"
    override val description: String
        get() = """
            Silence everyone in your voice channel, please only use for Among Us.
            ${String.format("Usage: `%sshh`", "/")}
            """.trimIndent()
}