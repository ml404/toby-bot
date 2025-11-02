package bot.toby.command.commands.music

import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.GuildMusicManager
import bot.toby.lavaplayer.PlayerManager
import core.command.Command
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import net.dv8tion.jda.api.interactions.InteractionHook

interface MusicCommand : Command {
    fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int = 5
    )

    companion object {
        @JvmStatic
        fun sendDeniedStoppableMessage(
            interactionHook: InteractionHook,
            musicManager: GuildMusicManager,
            deleteDelay: Int
        ) {
            val queueSize = musicManager.scheduler.queue.size
            if (queueSize > 0) {
                interactionHook
                    .sendMessage("Our daddy taught us not to be ashamed of our playlists")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
            } else {
                val duration = musicManager.audioPlayer.playingTrack.duration
                val songDuration = MusicPlayerHelper.formatTime(duration)
                interactionHook
                    .sendMessage("HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR $songDuration, $songDuration OF PLAYTIME!")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
            }
        }

        fun isInvalidChannelStateForCommand(ctx: CommandContext, deleteDelay: Int): Boolean {
            val self = ctx.selfMember!!
            val selfVoiceState = self.voiceState
            val memberVoiceState = ctx.member!!.voiceState
            val selfChannel = selfVoiceState!!.channel
            val memberChannel = memberVoiceState!!.channel
            val event = ctx.event

            if (!selfVoiceState.inAudioChannel()) {
                event.hook
                    .sendMessage("I need to be in a voice channel for this to work")
                    .setEphemeral(true)
                    .queue(invokeDeleteOnMessageResponse(deleteDelay))
                return true
            }

            if (!memberVoiceState.inAudioChannel() || memberChannel != selfChannel) {
                val errorMessage = if (!memberVoiceState.inAudioChannel()) {
                    "You need to be in a voice channel for this command to work"
                } else {
                    "You need to be in the same voice channel as me for this to work"
                }
                event.hook
                    .sendMessage(errorMessage)
                    .setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay))
                return true
            }
            return false
        }
    }
}
