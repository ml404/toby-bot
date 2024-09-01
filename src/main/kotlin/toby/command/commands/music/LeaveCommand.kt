package toby.command.commands.music

import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import toby.lavaplayer.PlayerManager

class LeaveCommand(private val configService: IConfigService) : IMusicCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int?
    ) {
        val event = ctx.event
        event.deferReply().queue()

        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay!!)
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
            IMusicCommand.sendDeniedStoppableMessage(event.hook, musicManager, deleteDelay)
        }
    }

    private fun handleStopCommand(
        event: SlashCommandInteractionEvent,
        selfVoiceState: GuildVoiceState?,
        guild: net.dv8tion.jda.api.entities.Guild,
        musicManager: toby.lavaplayer.GuildMusicManager,
        audioManager: net.dv8tion.jda.api.managers.AudioManager,
        deleteDelay: Int?
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

        event.hook
            .sendMessage("Disconnecting from `\uD83D\uDD0A ${selfVoiceState?.channel?.name}`")
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
    }

    override val name: String
        get() = "leave"

    override val description: String
        get() = "Makes the TobyBot leave the voice channel it's currently in"

    companion object {
        private fun isInvalidChannelStateForCommand(
            deleteDelay: Int?,
            event: SlashCommandInteractionEvent,
            selfVoiceState: GuildVoiceState?
        ): Boolean {
            return if (!selfVoiceState?.inAudioChannel()!!) {
                event.hook.sendMessage("I'm not in a voice channel, somebody shoot this guy")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                true
            } else {
                false
            }
        }
    }
}