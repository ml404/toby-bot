package bot.toby.button.buttons

import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager

class PausePlayButton : IButton {
    override val name: String
        get() = "pause/play"
    override val description: String
        get() = "Button used to play or pause on the nowplaying message"

    override fun handle(ctx: ButtonContext, requestingUserDto: bot.database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val guild = ctx.guild
        val musicManager = PlayerManager.instance.getMusicManager(guild)

        MusicPlayerHelper.changePauseStatusOnTrack(event, musicManager, deleteDelay ?: 0)
    }
}