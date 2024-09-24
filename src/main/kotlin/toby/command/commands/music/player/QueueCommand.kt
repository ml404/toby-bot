package toby.command.commands.music.player

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.command.commands.music.IMusicCommand
import toby.helpers.MusicPlayerHelper
import toby.jpa.dto.UserDto
import toby.lavaplayer.PlayerManager
import kotlin.math.min

class QueueCommand : IMusicCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply(true).queue()
        val queue = instance.getMusicManager(ctx.guild).scheduler.queue
        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }
        if (queue.isEmpty()) {
            event.hook.sendMessage("The queue is currently empty").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        val trackCount = min(queue.size.toDouble(), 20.0).toInt()
        val trackList: List<AudioTrack?> = queue.toList()
        val messageAction = event.hook.sendMessage("**Current Queue:**\n")
        for (i in 0 until trackCount) {
            val track = trackList[i]
            val info = track?.info
            messageAction.addContent("#")
                    .addContent((i + 1).toString())
                    .addContent(" `")
                    .addContent(info?.title.toString())
                    .addContent(" by ")
                    .addContent(info?.author!!)
                    .addContent("` [`")
                    .addContent(MusicPlayerHelper.formatTime(track.duration))
                    .addContent("`]\n")
        }
        if (trackList.size > trackCount) {
            messageAction.addContent("And `")
                    .addContent((trackList.size - trackCount).toString())
                    .addContent("` more...")
        }
        messageAction.setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    override val name: String
        get() = "queue"
    override val description: String
        get() = "shows the queued up songs"
}
