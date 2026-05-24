package bot.toby.command.commands.music.channel

import bot.toby.command.commands.music.MusicCommand
import bot.toby.lavaplayer.GuildMusicManager
import bot.toby.lavaplayer.PlayerManager
import bot.toby.voice.LastConnectedChannelTracker
import core.command.Command.Companion.replyAndDelete
import core.command.CommandContext
import database.dto.ConfigDto
import database.dto.UserDto
import database.service.guild.ConfigService
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.managers.AudioManager
import org.springframework.stereotype.Component

@Component
class LeaveCommand(
    private val configService: ConfigService,
    private val lastConnectedChannelTracker: LastConnectedChannelTracker,
) : MusicCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int
    ) {
        val event = ctx.event
        event.deferReply().queue()

        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay)
            return
        }

        val selfVoiceState = ctx.selfMember?.voiceState
        if (isInvalidChannelStateForCommand(deleteDelay, event, selfVoiceState)) return

        val guild = event.guild!!
        val audioManager = guild.audioManager
        val musicManager = instance.getMusicManager(guild)

        if (instance.isCurrentlyStoppable || requestingUserDto.superUser) {
            handleStopCommand(event, selfVoiceState, guild, musicManager, audioManager, deleteDelay)
        } else {
            MusicCommand.sendDeniedStoppableMessage(event.hook, musicManager, deleteDelay)
        }
    }

    private fun handleStopCommand(
        event: SlashCommandInteractionEvent,
        selfVoiceState: GuildVoiceState?,
        guild: net.dv8tion.jda.api.entities.Guild,
        musicManager: GuildMusicManager,
        audioManager: AudioManager,
        deleteDelay: Int
    ) {
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val defaultVolume = configService.getConfigByName(volumePropertyName, guild.id)?.value?.toInt() ?: 100

        musicManager.scheduler.apply {
            isLooping = false
            queue.clear()
        }
        musicManager.audioPlayer.apply {
            stopTrack()
            volume = defaultVolume
        }
        audioManager.closeAudioConnection()
        lastConnectedChannelTracker.clear(event.guild!!.idLong)

        event.hook.replyAndDelete(
            "Disconnecting from `\uD83D\uDD0A ${selfVoiceState?.channel?.name}`",
            deleteDelay,
        )
    }

    override val name: String
        get() = "leave"

    override val description: String
        get() = "Makes the TobyBot leave the voice channel it's currently in"

    companion object {
        private fun isInvalidChannelStateForCommand(
            deleteDelay: Int,
            event: SlashCommandInteractionEvent,
            selfVoiceState: GuildVoiceState?
        ): Boolean {
            return if (!selfVoiceState?.inAudioChannel()!!) {
                event.hook.replyAndDelete("I'm not in a voice channel, somebody shoot this guy", deleteDelay)
                true
            } else {
                false
            }
        }
    }
}