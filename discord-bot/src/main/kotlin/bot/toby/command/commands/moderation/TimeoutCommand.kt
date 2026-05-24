package bot.toby.command.commands.moderation

import bot.toby.command.PermissionValidator
import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class TimeoutCommand @Autowired constructor(
    private val permissionValidator: PermissionValidator,
) : ModerationCommand {
    companion object {
        private const val USERS = "users"
        private const val MINUTES = "minutes"
        private const val REASON = "reason"

        // Discord caps timeouts at 28 days.
        private const val MAX_MINUTES = 28L * 24L * 60L
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild!!
        val member = ctx.member ?: return
        val botMember = guild.selfMember

        val mentionedMembers = event.getOption(USERS)?.mentions?.members
        if (mentionedMembers.isNullOrEmpty()) {
            event.hook.replyAndDelete("You must mention 1 or more Users to timeout.", deleteDelay)
            return
        }

        val minutes = event.getOption(MINUTES)?.asLong ?: 10L
        if (minutes < 1 || minutes > MAX_MINUTES) {
            event.hook.replyAndDelete("Duration must be between 1 and $MAX_MINUTES minutes.", deleteDelay)
            return
        }
        val reason = event.getOption(REASON)?.asString?.takeIf { it.isNotBlank() }
            ?: "Timed out via /timeout."

        mentionedMembers.forEach { target ->
            if (!permissionValidator.actorMayActOn(
                    event, member, target, Permission.MODERATE_MEMBERS, "timeout", deleteDelay
                )
            ) return@forEach
            if (!permissionValidator.botMayAct(
                    event, botMember, target, Permission.MODERATE_MEMBERS, "timeout", deleteDelay,
                    requireCanInteract = true,
                )
            ) return@forEach
            target.timeoutFor(Duration.ofMinutes(minutes)).reason(reason).queue(
                {
                    event.hook.replyAndDelete(
                        "Timed out ${target.effectiveName} for $minutes minute(s).",
                        deleteDelay,
                    )
                },
                { error ->
                    event.hook.replyAndDelete("Could not timeout: ${error.message}", deleteDelay)
                }
            )
        }
    }

    override val name: String get() = "timeout"
    override val description: String get() = "Timeout one or more members for a number of minutes."

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.STRING, USERS, "User(s) to timeout", true),
            OptionData(OptionType.INTEGER, MINUTES, "Duration in minutes (1-40320, default 10)", false)
                .setMinValue(1).setMaxValue(MAX_MINUTES),
            OptionData(OptionType.STRING, REASON, "Reason for the timeout", false),
        )
}
