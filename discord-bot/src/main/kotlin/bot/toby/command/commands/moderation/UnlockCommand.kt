package bot.toby.command.commands.moderation

import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.springframework.stereotype.Component
import java.util.EnumSet

@Component
class UnlockCommand : ModerationCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild!!
        val member = ctx.member ?: return
        val botMember = guild.selfMember
        val channel = event.channel

        if (channel.type != ChannelType.TEXT) {
            event.hook.replyAndDelete("Unlock only works in text channels.", deleteDelay)
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
            .clear(EnumSet.of(Permission.MESSAGE_SEND))
            .reason("Unlocked via /unlock.")
            .queue(
                { event.hook.replyAndDelete("Unlocked ${textChannel.asMention}.", deleteDelay) },
                { error -> event.hook.replyAndDelete("Could not unlock: ${error.message}", deleteDelay) }
            )
    }

    override val name: String get() = "unlock"
    override val description: String get() = "Restore @everyone's ability to send messages in this channel."
}
