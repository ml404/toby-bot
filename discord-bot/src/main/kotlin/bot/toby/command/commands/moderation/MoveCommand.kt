package bot.toby.command.commands.moderation

import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.ConfigDto
import database.dto.UserDto
import database.service.ConfigService
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class MoveCommand @Autowired constructor(private val configService: ConfigService) : ModerationCommand {

    companion object {
        private const val USERS = "users"
        private const val CHANNEL = "channel"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: return
        val member = ctx.member ?: return
        val botMember = guild.selfMember

        val memberList = event.getOption(USERS)?.mentions?.members.orEmpty()
        if (memberList.isEmpty()) {
            event.hook.replyAndDelete("You must mention 1 or more Users to move", deleteDelay)
            return
        }

        val voiceChannel = getVoiceChannel(event, guild) ?: run {
            event.hook.replyAndDelete("Could not find a channel on the server that matched the name", deleteDelay)
            return
        }

        memberList.forEach { target ->
            if (!validateChannel(event, botMember, member, target, deleteDelay)) {
                guild.moveVoiceMember(target, voiceChannel).queue(
                    {
                        event.hook.replyAndDelete(
                            "Moved ${target.effectiveName} to '${voiceChannel.name}'",
                            deleteDelay,
                        )
                    },
                    { error ->
                        event.hook.replyAndDelete(
                            "Could not move '${target.effectiveName}': ${error.message}",
                            deleteDelay,
                        )
                    }
                )
            }
        }
    }

    private fun getVoiceChannel(event: SlashCommandInteractionEvent, guild: Guild) =
        event.getOption(CHANNEL)?.asChannel?.asVoiceChannel()
            ?: configService.getConfigByName(ConfigDto.Configurations.MOVE.configValue, guild.id)
                ?.let { config -> guild.getVoiceChannelsByName(config.value ?: "", true).firstOrNull() }

    private fun validateChannel(
        event: SlashCommandInteractionEvent,
        botMember: Member,
        member: Member,
        target: Member,
        deleteDelay: Int
    ): Boolean {
        return when {
            target.voiceState?.inAudioChannel() == false -> {
                event.hook.replyAndDelete(
                    "Mentioned user '${target.effectiveName}' is not connected to a voice channel currently, so cannot be moved.",
                    deleteDelay,
                )
                true
            }
            !member.canInteract(target) || !member.hasPermission(Permission.VOICE_MOVE_OTHERS) -> {
                event.hook.replyAndDelete("You can't move '${target.effectiveName}'", deleteDelay)
                true
            }
            !botMember.hasPermission(Permission.VOICE_MOVE_OTHERS) -> {
                event.hook.replyAndDelete("I'm not allowed to move ${target.effectiveName}", deleteDelay)
                true
            }
            else -> false
        }
    }

    override val name: String
        get() = "move"

    override val description: String
        get() = "Move mentioned members into a voice channel (voice channel can be defaulted by config command)"

    override val optionData: List<OptionData>
        get() = listOf(
            OptionData(OptionType.STRING, USERS, "User(s) to move", true),
            OptionData(OptionType.STRING, CHANNEL, "Channel to move to")
        )
}
