package toby.command.commands.music

import toby.command.CommandContext
import toby.command.ICommand.Companion.deleteAfter
import toby.helpers.MusicPlayerHelper
import toby.jpa.dto.UserDto
import toby.lavaplayer.PlayerManager

class ResumeCommand : IMusicCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(ctx: CommandContext, instance: PlayerManager, requestingUserDto: UserDto, deleteDelay: Int?) {
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
