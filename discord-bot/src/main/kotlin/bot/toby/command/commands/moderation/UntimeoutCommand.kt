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

@Component
class UntimeoutCommand @Autowired constructor(
    private val permissionValidator: PermissionValidator,
) : ModerationCommand {
    companion object {
        private const val USERS = "users"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild!!
        val member = ctx.member ?: return
        val botMember = guild.selfMember

        val mentionedMembers = event.getOption(USERS)?.mentions?.members
        if (mentionedMembers.isNullOrEmpty()) {
            event.hook.replyAndDelete("You must mention 1 or more Users to untimeout.", deleteDelay)
            return
        }

        mentionedMembers.forEach { target ->
            if (!permissionValidator.actorMayActOn(
                    event, member, target, Permission.MODERATE_MEMBERS, "untimeout", deleteDelay
                )
            ) return@forEach
            if (!permissionValidator.botMayAct(
                    event, botMember, target, Permission.MODERATE_MEMBERS, "untimeout", deleteDelay,
                    requireCanInteract = true,
                )
            ) return@forEach
            target.removeTimeout().reason("Cleared via /untimeout.").queue(
                {
                    event.hook.replyAndDelete(
                        "Removed timeout from ${target.effectiveName}.",
                        deleteDelay,
                    )
                },
                { error ->
                    event.hook.replyAndDelete("Could not remove timeout: ${error.message}", deleteDelay)
                }
            )
        }
    }

    override val name: String get() = "untimeout"
    override val description: String get() = "Remove an active timeout from one or more members."

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.STRING, USERS, "User(s) to remove timeout from", true)
        )
}
