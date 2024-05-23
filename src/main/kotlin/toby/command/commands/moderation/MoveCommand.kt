package toby.command.commands.moderation

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import java.util.*
import java.util.function.Consumer

class MoveCommand(private val configService: IConfigService) : IModerationCommand {
    private val USERS = "users"
    private val CHANNEL = "channel"
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val member = ctx.member
        val guild = event.guild!!
        val memberList = Optional.ofNullable(event.getOption(USERS)).map { obj: OptionMapping -> obj.mentions }
            .map { obj: Mentions -> obj.members }.orElse(emptyList())
        if (memberList.isEmpty()) {
            event.hook.sendMessage("You must mention 1 or more Users to move")
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        val channelOptional =
            Optional.ofNullable(event.getOption(CHANNEL)).map { obj: OptionMapping -> obj.getAsChannel() }
        val channelConfig = configService.getConfigByName(ConfigDto.Configurations.MOVE.configValue, guild.id)
        val voiceChannelOptional = channelOptional.map { obj: GuildChannelUnion -> obj.asVoiceChannel() }
            .or { guild.getVoiceChannelsByName(channelConfig?.value!!, true).stream().findFirst() }
        if (voiceChannelOptional.isEmpty) {
            event.hook.sendMessageFormat("Could not find a channel on the server that matched name")
                .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        memberList.forEach(Consumer { target: Member ->
            if (doChannelValidation(ctx.event, guild.selfMember, member, target, deleteDelay!!)) return@Consumer
            val voiceChannel = voiceChannelOptional.get()
            guild.moveVoiceMember(target, voiceChannel)
                .queue(
                    {
                        event.hook.sendMessageFormat("Moved %s to '%s'", target.effectiveName, voiceChannel.name)
                            .queue(invokeDeleteOnMessageResponse(deleteDelay))
                    }
                ) { error: Throwable ->
                    event.hook.sendMessageFormat("Could not move '%s'", error.message)
                        .queue(invokeDeleteOnMessageResponse(deleteDelay))
                }
        })
    }

    private fun doChannelValidation(
        event: SlashCommandInteractionEvent,
        botMember: Member,
        member: Member?,
        target: Member,
        deleteDelay: Int
    ): Boolean {
        if (!target.voiceState!!.inAudioChannel()) {
            event.hook.sendMessageFormat(
                "Mentioned user '%s' is not connected to a voice channel currently, so cannot be moved.",
                target.effectiveName
            ).queue(invokeDeleteOnMessageResponse(deleteDelay))
            return true
        }
        if (!member!!.canInteract(target) || !member.hasPermission(Permission.VOICE_MOVE_OTHERS)) {
            event.hook.sendMessageFormat("You can't move '%s'", target.effectiveName)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return true
        }
        if (!botMember.hasPermission(Permission.VOICE_MOVE_OTHERS)) {
            event.hook.sendMessageFormat("I'm not allowed to move %s", target.effectiveName)
                .queue(invokeDeleteOnMessageResponse(deleteDelay))
            return true
        }
        return false
    }

    override val name: String get() = "move"
    override val description: String get() = "Move mentioned members into a voice channel (voice channel can be defaulted by config command)"
    override val optionData: List<OptionData> get() = listOf(
            OptionData(OptionType.STRING, USERS, "User(s) to move", true),
            OptionData(OptionType.STRING, CHANNEL, "Channel to move to")
        )
}