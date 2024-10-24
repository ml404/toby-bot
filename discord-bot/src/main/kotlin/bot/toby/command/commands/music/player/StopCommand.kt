package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager
import core.command.CommandContext
import org.springframework.stereotype.Component

@Component
class StopCommand : MusicCommand {
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
        if (MusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
        MusicPlayerHelper.stopSong(event, instance.getMusicManager(event.guild!!), requestingUserDto.superUser, deleteDelay)
    }

    override val name: String
        get() = "stop"
    override val description: String
        get() = "Stops the current song and clears the queue"
}
