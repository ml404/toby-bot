package toby.command.commands.music

import toby.command.CommandContext
import toby.helpers.MusicPlayerHelper
import toby.jpa.dto.UserDto
import toby.lavaplayer.PlayerManager

class PauseCommand : IMusicCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay!!)
            return
        }
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
        val guild = event.guild!!
        val musicManager = instance.getMusicManager(guild)
        if (instance.isCurrentlyStoppable || requestingUserDto.superUser) {
            MusicPlayerHelper.changePauseStatusOnTrack(event, musicManager, deleteDelay!!)
        } else {
            IMusicCommand.sendDeniedStoppableMessage(event.hook, musicManager, deleteDelay)
        }
    }

    override val name: String
        get() = "pause"
    override val description: String
        get() = "Pauses the current song if one is playing"
}
