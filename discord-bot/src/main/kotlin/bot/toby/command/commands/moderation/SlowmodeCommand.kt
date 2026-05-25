package bot.toby.command.commands.moderation

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
class SlowmodeCommand : ModerationCommand {
    companion object {
        private const val SECONDS = "seconds"

        // Discord's per-channel slowmode cap.
        private const val MAX_SECONDS = 21_600L
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        val guild = event.guild!!
        val member = ctx.member ?: return
        val botMember = guild.selfMember
        val channel = event.channel

        if (channel.type != ChannelType.TEXT) {
            event.hook.replyAndDelete("Slowmode only works in text channels.", deleteDelay)
            return
        }
        val textChannel = channel as TextChannel

        if (!member.hasPermission(textChannel, Permission.MANAGE_CHANNEL)) {
            event.hook.replyAndDelete("You need Manage Channel in this channel.", deleteDelay)
            return
        }
        if (!botMember.hasPermission(textChannel, Permission.MANAGE_CHANNEL)) {
            event.hook.replyAndDelete("I need Manage Channel in this channel.", deleteDelay)
            return
        }

        val seconds = event.getOption(SECONDS)?.asLong ?: 0L
        if (seconds < 0 || seconds > MAX_SECONDS) {
            event.hook.replyAndDelete("Seconds must be between 0 and $MAX_SECONDS.", deleteDelay)
            return
        }

        textChannel.manager.setSlowmode(seconds.toInt()).reason("Set via /slowmode.").queue(
            {
                val msg = if (seconds == 0L) "Disabled slowmode in ${textChannel.asMention}."
                else "Set slowmode to ${seconds}s in ${textChannel.asMention}."
                event.hook.replyAndDelete(msg, deleteDelay)
            },
            { error -> event.hook.replyAndDelete("Could not set slowmode: ${error.message}", deleteDelay) }
        )
    }

    override val name: String get() = "slowmode"
    override val description: String get() = "Set per-channel slowmode in seconds (0 to disable, max 21600)."

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.INTEGER, SECONDS, "Slowmode delay in seconds (0-21600)", true)
                .setMinValue(0).setMaxValue(MAX_SECONDS)
        )
}
