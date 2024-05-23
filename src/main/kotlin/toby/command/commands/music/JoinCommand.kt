package toby.command.commands.music

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
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
        val self = ctx.selfMember
        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }
        val memberVoiceState = doJoinChannelValidation(ctx, deleteDelay) ?: return
        val audioManager = event.guild!!.audioManager
        val memberChannel: AudioChannel? = memberVoiceState.channel
        if (self!!.hasPermission(Permission.VOICE_CONNECT)) {
            audioManager.openAudioConnection(memberChannel)
            val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
            val databaseConfig = configService.getConfigByName(volumePropertyName, event.guild!!.id)
            val defaultVolume = databaseConfig?.value?.toInt() ?: 100
            instance.getMusicManager(event.guild!!).audioPlayer.volume = defaultVolume
            event.hook.sendMessageFormat("Connecting to `\uD83D\uDD0A %s` with volume '%s'", memberChannel!!.name, defaultVolume).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    private fun doJoinChannelValidation(ctx: CommandContext, deleteDelay: Int?): GuildVoiceState? {
        val self = ctx.selfMember
        val selfVoiceState = self!!.voiceState
        val event = ctx.event
        if (selfVoiceState!!.inAudioChannel()) {
            event.hook.sendMessage("I'm already in a voice channel").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return null
        }
        val member = ctx.member
        val memberVoiceState = member!!.voiceState
        if (!memberVoiceState!!.inAudioChannel()) {
            event.hook.sendMessage("You need to be in a voice channel for this command to work").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return null
        }
        return memberVoiceState
    }

    override val name: String
        get() = "join"
    override val description: String
        get() = "Makes the bot join your voice channel"
}
