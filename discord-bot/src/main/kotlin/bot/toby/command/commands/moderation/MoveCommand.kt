package bot.toby.command.commands.moderation

import bot.database.dto.ConfigDto
import bot.database.service.IConfigService
import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData

class MoveCommand(private val configService: IConfigService) : IModerationCommand {

    companion object {
        private const val USERS = "users"
        private const val CHANNEL = "channel"
    }

    override fun handle(ctx: CommandContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()

        val guild = event.guild ?: return
        val member = ctx.member ?: return
        val botMember = guild.selfMember

        val memberList = event.getOption(USERS)?.mentions?.members.orEmpty()
        if (memberList.isEmpty()) {
            event.hook.sendMessage("You must mention 1 or more Users to move")
                .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
            return
        }

        val voiceChannel = getVoiceChannel(event, guild) ?: run {
            event.hook.sendMessage("Could not find a channel on the server that matched the name")
                .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
            return
        }

        memberList.forEach { target ->
            if (!validateChannel(event, botMember, member, target, deleteDelay ?: 0)) {
                guild.moveVoiceMember(target, voiceChannel).queue(
                    { event.hook.sendMessage("Moved ${target.effectiveName} to '${voiceChannel.name}'")
                        .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0)) },
                    { error -> event.hook.sendMessage("Could not move '${target.effectiveName}': ${error.message}")
                        .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0)) }
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
                event.hook.sendMessage("Mentioned user '${target.effectiveName}' is not connected to a voice channel currently, so cannot be moved.")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
                true
            }
            !member.canInteract(target) || !member.hasPermission(Permission.VOICE_MOVE_OTHERS) -> {
                event.hook.sendMessage("You can't move '${target.effectiveName}'")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
                true
            }
            !botMember.hasPermission(Permission.VOICE_MOVE_OTHERS) -> {
                event.hook.sendMessage("I'm not allowed to move ${target.effectiveName}")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
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
