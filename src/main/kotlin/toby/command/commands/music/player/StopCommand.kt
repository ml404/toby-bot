package toby.command.commands.music.player

import toby.command.CommandContext
import toby.command.commands.music.IMusicCommand
import toby.helpers.MusicPlayerHelper
import toby.jpa.dto.UserDto
import toby.lavaplayer.PlayerManager

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
