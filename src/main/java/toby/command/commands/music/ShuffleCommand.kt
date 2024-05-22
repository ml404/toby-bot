package toby.command.commands.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import toby.lavaplayer.PlayerManager
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

class ShuffleCommand : IMusicCommand {
    override fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext?, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx!!.event
        event.deferReply().queue()
        if (requestingUserDto.musicPermission) {
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
            val guild = event.guild!!
            val trackScheduler = instance.getMusicManager(guild).scheduler
            val queue = trackScheduler.queue
            if (queue.size == 0) {
                event.hook.sendMessage("I can't shuffle a queue that doesn't exist").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
                return
            }
            val shuffledAudioTracks = shuffleAudioTracks(queue)
            trackScheduler.queue = shuffledAudioTracks
            event.hook.sendMessage("The queue has been shuffled 🦧").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    private fun shuffleAudioTracks(queue: BlockingQueue<AudioTrack?>): LinkedBlockingQueue<AudioTrack?> {
        val audioTrackArrayList = ArrayList(queue)
        Collections.shuffle(audioTrackArrayList)
        return LinkedBlockingQueue(audioTrackArrayList)
    }

    override val name: String
        get() = "shuffle"
    override val description: String
        get() = "Use this command to shuffle the queue"
}
