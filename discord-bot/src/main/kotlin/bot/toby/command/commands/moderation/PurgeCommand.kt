package bot.toby.command.commands.moderation

import bot.toby.moderation.MessagePurge
import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component

@Component
class PurgeCommand : ModerationCommand {
    companion object {
        private const val COUNT = "count"
        private const val USER = "user"
        private const val MAX_COUNT = 100L
    }

    override val ephemeral: Boolean = true

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        val guild = event.guild!!
        val member = ctx.member ?: return
        val channel = event.channel
        val botMember = guild.selfMember

        if (channel.type != ChannelType.TEXT) {
            event.hook.replyAndDelete("Purge only works in text channels.", deleteDelay)
            return
        }
        val textChannel = channel as TextChannel

        if (!member.hasPermission(textChannel, Permission.MESSAGE_MANAGE)) {
            event.hook.replyAndDelete("You need Manage Messages in this channel.", deleteDelay)
            return
        }
        if (!botMember.hasPermission(textChannel, Permission.MESSAGE_MANAGE)) {
            event.hook.replyAndDelete("I need Manage Messages in this channel.", deleteDelay)
            return
        }

        val count = event.getOption(COUNT)?.asLong ?: 10L
        if (count < 1 || count > MAX_COUNT) {
            event.hook.replyAndDelete("Count must be between 1 and $MAX_COUNT.", deleteDelay)
            return
        }
        val filterUserId = event.getOption(USER)?.asUser?.idLong

        textChannel.history.retrievePast(count.toInt()).queue({ history ->
            val matching = if (filterUserId != null) {
                history.filter { it.author.idLong == filterUserId }
            } else history
            // Discord's 14-day bulk-delete rule and the one-versus-many split
            // both live in MessagePurge, shared with the right-click entry.
            val scope = MessagePurge.deletable(matching)
            if (scope.messages.isEmpty()) {
                val msg = if (scope.tooOld > 0) "No deletable messages — ${scope.tooOld} older than 14 days."
                else "No matching messages found."
                event.hook.replyAndDelete(msg, deleteDelay)
                return@queue
            }
            val plural = if (scope.messages.size == 1) "" else "s"
            MessagePurge.delete(textChannel, scope.messages, "Purged via /purge.").queue(
                {
                    event.hook.replyAndDelete(
                        "Deleted ${scope.messages.size} message$plural${scope.suffix}.", deleteDelay
                    )
                },
                { error -> event.hook.replyAndDelete("Could not purge: ${error.message}", deleteDelay) }
            )
        }, { error -> event.hook.replyAndDelete("Could not fetch history: ${error.message}", deleteDelay) })
    }

    override val name: String get() = "purge"
    override val description: String get() =
        "Bulk-delete the last N messages in this channel (max 100, optionally filtered to one user)."

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.INTEGER, COUNT, "How many recent messages to scan (1-100, default 10)", false)
                .setMinValue(1).setMaxValue(MAX_COUNT),
            OptionData(OptionType.USER, USER, "Only delete messages from this user", false),
        )
}
