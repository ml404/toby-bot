package toby.button.buttons

import toby.button.ButtonContext
import toby.button.IButton
import toby.helpers.MusicPlayerHelper
import toby.jpa.dto.UserDto
import toby.lavaplayer.PlayerManager

class StopButton : IButton {
    override val name: String
        get() = "stop"
    override val description: String
        get() = "Stop the currently playing song"

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val musicManager = PlayerManager.instance.getMusicManager(event.guild!!)
        MusicPlayerHelper.stopSong(event, musicManager, requestingUserDto.superUser, deleteDelay)
    }
}