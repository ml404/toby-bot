package toby.command.commands.music

import net.dv8tion.jda.api.interactions.InteractionHook
import toby.command.CommandContext
import toby.command.ICommand
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.helpers.MusicPlayerHelper
import toby.jpa.dto.UserDto
import toby.lavaplayer.GuildMusicManager
import toby.lavaplayer.PlayerManager

interface IMusicCommand : ICommand {
    fun handleMusicCommand(ctx: CommandContext?, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?)

    companion object {
        @JvmStatic
        fun sendDeniedStoppableMessage(interactionHook: InteractionHook, musicManager: GuildMusicManager, deleteDelay: Int?) {
            if (musicManager.scheduler.queue.size > 0) {
                interactionHook.sendMessage("Our daddy taught us not to be ashamed of our playlists").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            } else {
                val duration = musicManager.audioPlayer.playingTrack.duration
                val songDuration = MusicPlayerHelper.formatTime(duration)
                interactionHook.sendMessageFormat("HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR %s, %s OF PLAYTIME!", songDuration, songDuration).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
        }

        fun isInvalidChannelStateForCommand(ctx: CommandContext, deleteDelay: Int?): Boolean {
            val self = ctx.selfMember
            val selfVoiceState = self!!.voiceState
            val event = ctx.event
            if (!selfVoiceState!!.inAudioChannel()) {
                event.hook.sendMessage("I need to be in a voice channel for this to work").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return true
            }
            val member = ctx.member
            val memberVoiceState = member!!.voiceState
            if (!memberVoiceState!!.inAudioChannel()) {
                event.hook.sendMessage("You need to be in a voice channel for this command to work").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return true
            }
            if (memberVoiceState.channel != selfVoiceState.channel) {
                event.hook.sendMessage("You need to be in the same voice channel as me for this to work").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return true
            }
            return false
        }
    }
}
