package toby.command.commands.music

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.lavaplayer.PlayerManager

class LeaveCommand(private val configService: IConfigService) : IMusicCommand {
    override fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext?, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx!!.event
        event.deferReply().queue()
        val self = ctx.selfMember
        val selfVoiceState = self!!.voiceState
        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }
        if (isInvalidChannelStateForCommand(deleteDelay, event, selfVoiceState)) return
        val member = ctx.member
        val guild = event.guild!!
        val audioManager = guild.audioManager
        val musicManager = instance.getMusicManager(guild)
        if (PlayerManager.instance.isCurrentlyStoppable || member!!.hasPermission(Permission.KICK_MEMBERS)) {
            val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
            val databaseConfig = configService.getConfigByName(volumePropertyName, guild.id)
            val defaultVolume = databaseConfig?.value?.toInt() ?: 100
            musicManager.scheduler.isLooping = false
            musicManager.scheduler.queue.clear()
            musicManager.audioPlayer.stopTrack()
            musicManager.audioPlayer.volume = defaultVolume
            audioManager.closeAudioConnection()
            event.hook.sendMessageFormat("Disconnecting from `\uD83D\uDD0A %s`", selfVoiceState!!.channel!!.name).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else {
            IMusicCommand.sendDeniedStoppableMessage(event.hook, musicManager, deleteDelay)
        }
    }

    override val name: String
        get() = "leave"
    override val description: String
        get() = "Makes the TobyBot leave the voice channel it's currently in"

    companion object {
        private fun isInvalidChannelStateForCommand(deleteDelay: Int?, event: SlashCommandInteractionEvent, selfVoiceState: GuildVoiceState?): Boolean {
            if (!selfVoiceState!!.inAudioChannel()) {
                event.hook.sendMessage("I'm not in a voice channel, somebody shoot this guy").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return true
            }
            return false
        }
    }
}
