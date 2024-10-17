package bot.toby.command.commands.music.player

import bot.toby.command.CommandContext
import bot.toby.command.ICommand.Companion.deleteAfter
import bot.toby.command.commands.music.IMusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager
import org.springframework.stereotype.Component

@Component
class ResumeCommand : IMusicCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: database.dto.UserDto,
        deleteDelay: Int?
    ) {
        ctx.event.hook.deleteAfter(deleteDelay ?: 0)
        val event = ctx.event
        event.deferReply().queue()
        if (!requestingUserDto.musicPermission) {
            sendErrorMessage(event, deleteDelay ?: 0)
            return
        }
        if (IMusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
        MusicPlayerHelper.changePauseStatusOnTrack(event, instance.getMusicManager(ctx.guild), deleteDelay ?: 0)
    }

    override val name: String
        get() = "resume"
    override val description: String
        get() = "Resumes the current song if one is paused."
}
