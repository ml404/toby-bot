package toby.command.commands.music

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.handler.Handler
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.lavaplayer.PlayerManager

class JoinCommand(private val configService: IConfigService) : IMusicCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()

        val self = ctx.selfMember ?: return
        val memberVoiceState = doJoinChannelValidation(ctx, deleteDelay) ?: return
        val audioManager = event.guild!!.audioManager
        val memberChannel = memberVoiceState.channel?.asVoiceChannel()

        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }

        if (!self.hasPermission(Permission.VOICE_CONNECT)) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }

        audioManager.openAudioConnection(memberChannel)
        Handler.lastConnectedChannel[event.guild!!.idLong] = memberChannel!!
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val databaseConfig = configService.getConfigByName(volumePropertyName, event.guild?.id)
        val defaultVolume = databaseConfig?.value?.toInt() ?: 100
        instance.getMusicManager(event.guild!!).audioPlayer.volume = defaultVolume

        event.hook.sendMessage("Connecting to `\uD83D\uDD0A ${memberChannel.name}` with volume '$defaultVolume'").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun doJoinChannelValidation(ctx: CommandContext, deleteDelay: Int?): GuildVoiceState? {
        val self = ctx.selfMember ?: return null
        val selfVoiceState = self.voiceState
        val event = ctx.event

        if (selfVoiceState?.inAudioChannel() == true) {
            event.hook.sendMessage("I'm already in a voice channel").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return null
        }

        val member = ctx.member ?: return null
        val memberVoiceState = member.voiceState

        if (memberVoiceState?.inAudioChannel() != true) {
            event.hook.sendMessage("You need to be in a voice channel for this command to work").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return null
        }

        return memberVoiceState
    }

    override val name: String = "join"
    override val description: String = "Makes the bot join your voice channel"
}
