package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.lavaplayer.PlayerManager
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.UserDto
import org.springframework.stereotype.Component

@Component
class LoopCommand : MusicCommand {
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

        val scheduler = instance.getMusicManager(ctx.guild).scheduler
        val newIsRepeating = !scheduler.isLooping
        scheduler.isLooping = newIsRepeating

        val loopStatusMessage = if (newIsRepeating) "looping" else "not looping"
        event.hook
            .sendMessage("The Player has been set to **$loopStatusMessage**")
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
    override val name: String
        get() = "loop"
    override val description: String
        get() = "Loop the currently playing song"
}
