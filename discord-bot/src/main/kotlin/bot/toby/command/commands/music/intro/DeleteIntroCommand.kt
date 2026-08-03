package bot.toby.command.commands.music.intro

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.MenuHelper.DELETE_INTRO
import bot.toby.lavaplayer.PlayerManager
import core.command.CommandContext
import database.dto.user.UserDto
import org.springframework.stereotype.Component

/**
 * `/deleteintro` — pick an intro to remove. The confirmation carries an
 * **Undo** button ([bot.toby.button.buttons.music.UndoDeleteIntroButton]) so a
 * mis-click on the menu is recoverable, matching the web dashboard.
 */
@Component
class DeleteIntroCommand : MusicCommand {

    override val ephemeral: Boolean = true

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        handleMusicCommand(ctx, PlayerManager.instance, requestingUserDto, deleteDelay)
    }

    override fun handleMusicCommand(
        ctx: CommandContext,
        instance: PlayerManager,
        requestingUserDto: UserDto,
        deleteDelay: Int
    ) {
        sendIntroSelection(
            event = ctx.event,
            requestingUserDto = requestingUserDto,
            menuId = DELETE_INTRO,
            placeholder = "Select an intro to delete",
            emptyMessage = "You have no intros to delete.",
        )
    }

    override val name: String
        get() = "deleteintro"

    override val description: String
        get() = "Delete one of your intros."
}
