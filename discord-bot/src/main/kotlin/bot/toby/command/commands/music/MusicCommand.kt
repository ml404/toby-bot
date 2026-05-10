package bot.toby.command.commands.music

import bot.toby.lavaplayer.GuildMusicManager
import bot.toby.lavaplayer.PlayerManager
import bot.toby.util.formatTime
import core.command.Command
import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEphemeralAndDelete
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

    /**
     * Shared preamble for music commands: defers the reply, rejects users
     * without the music permission, and bails out when the bot/user aren't
     * in a compatible voice channel state. Returns `true` when the command
     * should proceed; implementers should `if (!checkMusicPreconditions(...)) return`.
     */
    fun checkMusicPreconditions(
        ctx: CommandContext,
        requestingUserDto: UserDto,
        deleteDelay: Int
    ): Boolean {
        val event = ctx.event
        event.deferReply().queue()
        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay)
            return false
        }
        if (isInvalidChannelStateForCommand(ctx, deleteDelay)) return false
        return true
    }

    companion object {
        @JvmStatic
        fun sendDeniedStoppableMessage(
            interactionHook: InteractionHook,
            musicManager: GuildMusicManager,
            deleteDelay: Int
        ) {
            val queueSize = musicManager.scheduler.queue.size
            if (queueSize > 0) {
                interactionHook.replyAndDelete(
                    "Our daddy taught us not to be ashamed of our playlists",
                    deleteDelay,
                )
            } else {
                val duration = musicManager.audioPlayer.playingTrack.duration
                val songDuration = formatTime(duration)
                interactionHook.replyAndDelete(
                    "HEY FREAK-SHOW! YOU AIN’T GOIN’ NOWHERE. I GOTCHA’ FOR $songDuration, $songDuration OF PLAYTIME!",
                    deleteDelay,
                )
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
                event.hook.replyEphemeralAndDelete(
                    "I need to be in a voice channel for this to work",
                    deleteDelay,
                )
                return true
            }

            if (!memberVoiceState.inAudioChannel() || memberChannel != selfChannel) {
                val errorMessage = if (!memberVoiceState.inAudioChannel()) {
                    "You need to be in a voice channel for this command to work"
                } else {
                    "You need to be in the same voice channel as me for this to work"
                }
                event.hook.replyEphemeralAndDelete(errorMessage, deleteDelay)
                return true
            }
            return false
        }
    }
}
