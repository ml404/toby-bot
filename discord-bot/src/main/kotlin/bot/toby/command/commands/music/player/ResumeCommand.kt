package bot.toby.command.commands.music.player

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager
import core.command.Command.Companion.deleteAfter
import core.command.CommandContext
import org.springframework.stereotype.Component

@Component
class ResumeCommand : MusicCommand {
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
        if (MusicCommand.isInvalidChannelStateForCommand(ctx, deleteDelay)) return
        MusicPlayerHelper.changePauseStatusOnTrack(event, instance.getMusicManager(ctx.guild), deleteDelay ?: 0)
    }

    override val name: String
        get() = "resume"
    override val description: String
        get() = "Resumes the current song if one is paused."
}
