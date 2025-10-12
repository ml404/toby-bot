package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager
import core.command.CommandContext
import database.dto.UserDto
import org.springframework.stereotype.Component

@Component
class PauseCommand : MusicCommand {
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
        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay)
            return
        }
        if (MusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
        val guild = event.guild!!
        val musicManager = instance.getMusicManager(guild)
        if (instance.isCurrentlyStoppable || requestingUserDto.superUser) {
            MusicPlayerHelper.changePauseStatusOnTrack(event, musicManager, deleteDelay)
        } else {
            MusicCommand.sendDeniedStoppableMessage(event.hook, musicManager, deleteDelay)
        }
    }

    override val name: String
        get() = "pause"
    override val description: String
        get() = "Pauses the current song if one is playing"
}
