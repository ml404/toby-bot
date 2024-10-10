package toby.button.buttons

import database.dto.UserDto
import toby.button.ButtonContext
import toby.button.IButton
import toby.helpers.MusicPlayerHelper
import toby.lavaplayer.PlayerManager

class PausePlayButton : IButton {
    override val name: String
        get() = "pause/play"
    override val description: String
        get() = "Button used to play or pause on the nowplaying message"

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val guild = ctx.guild
        val musicManager = PlayerManager.instance.getMusicManager(guild)

        MusicPlayerHelper.changePauseStatusOnTrack(event, musicManager, deleteDelay ?: 0)
    }
}