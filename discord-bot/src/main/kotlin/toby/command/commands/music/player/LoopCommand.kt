package toby.command.commands.music.player

import database.dto.UserDto
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.command.commands.music.IMusicCommand
import toby.lavaplayer.PlayerManager

class LoopCommand : IMusicCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int?
    ) {
        val event = ctx.event
        event.deferReply().queue()

        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay ?: 0)
            return
        }

        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return

        val scheduler = instance.getMusicManager(ctx.guild).scheduler
        val newIsRepeating = !scheduler.isLooping
        scheduler.isLooping = newIsRepeating

        val loopStatusMessage = if (newIsRepeating) "looping" else "not looping"
        event.hook
            .sendMessage("The Player has been set to **$loopStatusMessage**")
            .queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0))
    }
    override val name: String
        get() = "loop"
    override val description: String
        get() = "Loop the currently playing song"
}
