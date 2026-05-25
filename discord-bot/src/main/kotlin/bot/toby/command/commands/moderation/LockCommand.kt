package bot.toby.command.commands.moderation

import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.springframework.stereotype.Component
import java.util.EnumSet

@Component
class LockCommand : ModerationCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        val guild = event.guild!!
        val member = ctx.member ?: return
        val botMember = guild.selfMember
        val channel = event.channel

        if (channel.type != ChannelType.TEXT) {
            event.hook.replyAndDelete("Lock only works in text channels.", deleteDelay)
            return
        }
        val textChannel = channel as TextChannel

        if (!member.hasPermission(textChannel, Permission.MANAGE_PERMISSIONS)) {
            event.hook.replyAndDelete("You need Manage Permissions in this channel.", deleteDelay)
            return
        }
        if (!botMember.hasPermission(textChannel, Permission.MANAGE_PERMISSIONS)) {
            event.hook.replyAndDelete("I need Manage Permissions in this channel.", deleteDelay)
            return
        }

        val everyone = guild.publicRole
        textChannel.upsertPermissionOverride(everyone)
            .deny(EnumSet.of(Permission.MESSAGE_SEND))
            .reason("Locked via /lock.")
            .queue(
                { event.hook.replyAndDelete("Locked ${textChannel.asMention}.", deleteDelay) },
                { error -> event.hook.replyAndDelete("Could not lock: ${error.message}", deleteDelay) }
            )
    }

    override val name: String get() = "lock"
    override val description: String get() = "Prevent @everyone from sending messages in this channel."
}
