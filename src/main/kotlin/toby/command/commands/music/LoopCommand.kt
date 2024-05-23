package toby.command.commands.music

import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.UserDto
import toby.lavaplayer.PlayerManager

class LoopCommand : IMusicCommand {
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
        val scheduler = instance.getMusicManager(ctx.guild).scheduler
        val newIsRepeating = !scheduler.isLooping
        scheduler.isLooping = newIsRepeating
        event.hook.sendMessageFormat("The Player has been set to **%s**", if (newIsRepeating) "looping" else "not looping").queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    override val name: String
        get() = "loop"
    override val description: String
        get() = "Loop the currently playing song"
}
