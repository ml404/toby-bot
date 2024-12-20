package bot.toby.command.commands.moderation

import core.command.CommandContext
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.stereotype.Component

@Component
class KickCommand : ModerationCommand {
    companion object {
        private const val USERS = "users"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val deleteDelaySafe = deleteDelay ?: 0
        event.deferReply().queue()

        val guild = event.guild!!
        val member = ctx.member ?: return
        val botMember = guild.selfMember

        val mentionedMembers = event.getOption(USERS)
            ?.mentions
            ?.members

        if (mentionedMembers.isNullOrEmpty()) {
            event.hook.sendMessage("You must mention 1 or more Users to kick.").queue(
                core.command.Command.Companion.invokeDeleteOnMessageResponse(
                    deleteDelaySafe
                )
            )
            return
        }

        mentionedMembers.forEach { target ->
            when {
                !member.canInteract(target) || !member.hasPermission(Permission.KICK_MEMBERS) -> {
                    event.hook
                        .sendMessage("You can't kick ${target.effectiveName}")
                        .queue(core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelaySafe))
                }
                !botMember.canInteract(target) || !botMember.hasPermission(Permission.KICK_MEMBERS) -> {
                    event.hook
                        .sendMessage("I'm not allowed to kick ${target.effectiveName}")
                        .queue(core.command.Command.Companion.invokeDeleteOnMessageResponse(deleteDelaySafe))
                }
                else -> {
                    guild.kick(target).reason("because you told me to.").queue(
                        {
                            event.hook.sendMessage("Shot hit the mark... something about fortnite?").queue(
                                core.command.Command.Companion.invokeDeleteOnMessageResponse(
                                    deleteDelaySafe
                                )
                            )
                        },
                        { error ->
                            event.hook.sendMessage("Could not kick: ${error.message}").queue(
                                core.command.Command.Companion.invokeDeleteOnMessageResponse(
                                    deleteDelaySafe
                                )
                            )
                        }
                    )
                }
            }
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
