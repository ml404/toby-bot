package bot.toby.command.commands.music.intro

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.MenuHelper.EDIT_INTRO
import bot.toby.lavaplayer.PlayerManager
import core.command.CommandContext
import database.dto.user.UserDto
import org.springframework.stereotype.Component

/**
 * `/editintro` — pick an intro, then edit it in a modal
 * ([bot.toby.modal.modals.EditIntroModal]): name, volume and clip bounds in
 * one form. This used to change volume only, and only by typing a bare number
 * into the channel within ten seconds of selecting.
 */
@Component
class EditIntroCommand : MusicCommand {

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
            menuId = EDIT_INTRO,
            placeholder = "Select an intro to edit",
            emptyMessage = "You have no intros to edit. Add one with `/setintro`.",
        )
    }

    override val name: String
        get() = "editintro"

    override val description: String
        get() = "Edit one of your intros — name, volume and clip start/end."
}
