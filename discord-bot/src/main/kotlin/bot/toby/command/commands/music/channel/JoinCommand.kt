package bot.toby.command.commands.music.channel

import bot.toby.command.commands.music.MusicCommand
import bot.toby.handler.VoiceEventHandler.Companion.lastConnectedChannel
import bot.toby.lavaplayer.PlayerManager
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.ConfigDto
import database.service.ConfigService
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class JoinCommand @Autowired constructor(private val configService: ConfigService) : MusicCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: database.dto.UserDto,
        deleteDelay: Int?
    ) {
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
        lastConnectedChannel[event.guild!!.idLong] = memberChannel!!
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
