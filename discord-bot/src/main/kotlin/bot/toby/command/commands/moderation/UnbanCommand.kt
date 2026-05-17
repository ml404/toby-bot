package bot.toby.command.commands.moderation

import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component

@Component
class UnbanCommand : ModerationCommand {
    companion object {
        private const val USER_ID = "user_id"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild!!
        val member = ctx.member ?: return
        val botMember = guild.selfMember

        if (!member.hasPermission(Permission.BAN_MEMBERS)) {
            event.hook.replyAndDelete("You don't have the Ban Members permission.", deleteDelay)
            return
        }
        if (!botMember.hasPermission(Permission.BAN_MEMBERS)) {
            event.hook.replyAndDelete("I don't have the Ban Members permission.", deleteDelay)
            return
        }

        val rawId = event.getOption(USER_ID)?.asString?.trim()
        val userId = rawId?.toLongOrNull()
        if (userId == null) {
            event.hook.replyAndDelete("Provide a numeric user ID.", deleteDelay)
            return
        }

        guild.unban(UserSnowflake.fromId(userId)).reason("Unbanned via /unban.").queue(
            { event.hook.replyAndDelete("Unbanned <@$userId>.", deleteDelay) },
            { error -> event.hook.replyAndDelete("Could not unban: ${error.message}", deleteDelay) }
        )
    }

    override val name: String get() = "unban"
    override val description: String get() = "Unban a user by their Discord user ID."

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.STRING, USER_ID, "Discord user ID to unban", true)
        )
}
