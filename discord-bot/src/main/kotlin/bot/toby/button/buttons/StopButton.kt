package bot.toby.button.buttons

import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.helpers.MusicPlayerHelper
import bot.toby.lavaplayer.PlayerManager
import org.springframework.stereotype.Component

@Component
class StopButton : IButton {
    override val name: String
        get() = "stop"
    override val description: String
        get() = "Stop the currently playing song"

    override fun handle(ctx: ButtonContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        val musicManager = PlayerManager.instance.getMusicManager(event.guild!!)
        MusicPlayerHelper.stopSong(event, musicManager, requestingUserDto.superUser, deleteDelay)
    }
}