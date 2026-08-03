package bot.toby.command.commands.music.intro

import bot.toby.command.commands.music.MusicCommand
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.MenuHelper.EDIT_INTRO
import bot.toby.lavaplayer.PlayerManager
import core.command.CommandContext
import database.dto.user.UserDto
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/editintro` — pick an intro, then edit it in a modal
 * ([bot.toby.modal.modals.EditIntroModal]): name, volume and clip bounds in
 * one form. This used to change volume only, and only by typing a bare number
 * into the channel within ten seconds of selecting.
 */
@Component
class EditIntroCommand @Autowired constructor(
    private val introHelper: IntroHelper,
) : MusicCommand {

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
        val target = resolveIntroTarget(ctx.event, requestingUserDto, introHelper, deleteDelay) ?: return
        sendIntroSelection(
            event = ctx.event,
            target = target,
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
