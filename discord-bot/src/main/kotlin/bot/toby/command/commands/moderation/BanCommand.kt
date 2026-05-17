package bot.toby.command.commands.moderation

import bot.toby.command.PermissionValidator
import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class BanCommand @Autowired constructor(
    private val permissionValidator: PermissionValidator,
) : ModerationCommand {
    companion object {
        private const val USERS = "users"
        private const val REASON = "reason"
        private const val DELETE_DAYS = "delete_days"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild!!
        val member = ctx.member ?: return
        val botMember = guild.selfMember

        val mentionedMembers = event.getOption(USERS)?.mentions?.members
        if (mentionedMembers.isNullOrEmpty()) {
            event.hook.replyAndDelete("You must mention 1 or more Users to ban.", deleteDelay)
            return
        }

        val reason = event.getOption(REASON)?.asString?.takeIf { it.isNotBlank() }
            ?: "Banned via /ban."
        val deleteDays = event.getOption(DELETE_DAYS)?.asInt?.coerceIn(0, 7) ?: 0

        mentionedMembers.forEach { target ->
            if (!permissionValidator.actorMayActOn(
                    event, member, target, Permission.BAN_MEMBERS, "ban", deleteDelay
                )
            ) return@forEach
            if (!permissionValidator.botMayAct(
                    event, botMember, target, Permission.BAN_MEMBERS, "ban", deleteDelay,
                    requireCanInteract = true,
                )
            ) return@forEach
            guild.ban(target, deleteDays, TimeUnit.DAYS).reason(reason).queue(
                {
                    event.hook.replyAndDelete(
                        "Banned ${target.effectiveName}.",
                        deleteDelay,
                    )
                },
                { error ->
                    event.hook.replyAndDelete("Could not ban: ${error.message}", deleteDelay)
                }
            )
        }
    }

    override val name: String get() = "ban"
    override val description: String get() = "Ban one or more members from the server."

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.STRING, USERS, "User(s) to ban", true),
            OptionData(OptionType.STRING, REASON, "Reason for the ban", false),
            OptionData(OptionType.INTEGER, DELETE_DAYS, "Days of recent messages to delete (0-7)", false)
                .setMinValue(0).setMaxValue(7),
        )
}
