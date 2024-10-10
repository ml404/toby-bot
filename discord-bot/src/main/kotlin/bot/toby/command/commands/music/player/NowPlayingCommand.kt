package bot.toby.command.commands.music.player

import database.dto.UserDto
import bot.toby.command.CommandContext
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager

class NowPlayingCommand : IMusicCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
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