package bot.toby.command.commands.music.player

import database.dto.UserDto
import bot.toby.command.CommandContext
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager

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
            MusicPlayerHelper.changePauseStatusOnTrack(event, musicManager, deleteDelay ?: 0)
        } else {
            IMusicCommand.sendDeniedStoppableMessage(event.hook, musicManager, deleteDelay)
        }
    }

    override val name: String
        get() = "pause"
    override val description: String
        get() = "Pauses the current song if one is playing"
}
