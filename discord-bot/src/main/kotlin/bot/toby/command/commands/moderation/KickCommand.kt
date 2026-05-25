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
class KickCommand @Autowired constructor(
    private val permissionValidator: PermissionValidator,
) : ModerationCommand {
    companion object {
        private const val USERS = "users"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        val guild = event.guild!!
        val member = ctx.member ?: return
        val botMember = guild.selfMember

        val mentionedMembers = event.getOption(USERS)
            ?.mentions
            ?.members

        if (mentionedMembers.isNullOrEmpty()) {
            event.hook.replyAndDelete("You must mention 1 or more Users to kick.", deleteDelay)
            return
        }

        mentionedMembers.forEach { target ->
            val actorOk = permissionValidator.actorMayActOn(
                event, member, target, Permission.KICK_MEMBERS, "kick", deleteDelay
            )
            if (!actorOk) return@forEach
            val botOk = permissionValidator.botMayAct(
                event, botMember, target, Permission.KICK_MEMBERS, "kick", deleteDelay,
                requireCanInteract = true,
            )
            if (!botOk) return@forEach
            guild.kick(target).reason("because you told me to.").queue(
                {
                    event.hook.replyAndDelete(
                        "Shot hit the mark... something about fortnite?",
                        deleteDelay,
                    )
                },
                { error ->
                    event.hook.replyAndDelete("Could not kick: ${error.message}", deleteDelay)
                }
            )
        }
    }

    override val name: String
        get() = "kick"

    override val description: String
        get() = "Kick a member off the server."

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.STRING, USERS, "User(s) to kick", true)
        )
}
