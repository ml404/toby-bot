package bot.toby.command.commands.music.player

import bot.toby.command.CommandContext
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager
import org.springframework.stereotype.Component

@Component
class NowPlayingCommand : IMusicCommand {
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
            if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
            MusicPlayerHelper.nowPlaying(event, instance, deleteDelay)
        } else sendErrorMessage(event, deleteDelay!!)
    }

    override val name: String
        get() = "nowplaying"
    override val description: String
        get() = "Shows the currently playing song"
}
