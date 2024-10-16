package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.lavaplayer.PlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import core.command.CommandContext
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

@Component
class ShuffleCommand : MusicCommand {
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
        if (requestingUserDto.musicPermission) {
            if (MusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
            val guild = event.guild!!
            val trackScheduler = instance.getMusicManager(guild).scheduler
            val queue = trackScheduler.queue
            if (queue.size == 0) {
                event.hook.sendMessage("I can't shuffle a queue that doesn't exist").queue(
                    core.command.Command.Companion.invokeDeleteOnMessageResponse(
                        deleteDelay!!
                    )
                )
                return
            }
            val shuffledAudioTracks = shuffleAudioTracks(queue)
            trackScheduler.queue = shuffledAudioTracks
            event.hook.sendMessage("The queue has been shuffled 🦧").queue(
                core.command.Command.Companion.invokeDeleteOnMessageResponse(
                    deleteDelay!!
                )
            )
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
