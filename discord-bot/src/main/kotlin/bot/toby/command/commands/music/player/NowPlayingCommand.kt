package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager
import core.command.CommandContext
import database.dto.UserDto
import org.springframework.stereotype.Component

@Component
class NowPlayingCommand : MusicCommand {
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
        if (requestingUserDto.musicPermission) {
            if (MusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
            MusicPlayerHelper.nowPlaying(event, instance, deleteDelay)
        } else sendErrorMessage(event, deleteDelay)
    }

    override val name: String
        get() = "nowplaying"
    override val description: String
        get() = "Shows the currently playing song"
}
