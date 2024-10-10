package bot.toby.command.commands.music.player

import database.dto.UserDto
import bot.toby.command.CommandContext
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager

class StopCommand : IMusicCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
        MusicPlayerHelper.stopSong(event, instance.getMusicManager(event.guild!!), requestingUserDto.superUser, deleteDelay)
    }

    override val name: String
        get() = "stop"
    override val description: String
        get() = "Stops the current song and clears the queue"
}