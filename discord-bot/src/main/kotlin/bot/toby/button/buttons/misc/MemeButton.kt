package bot.toby.button.buttons.misc

import bot.toby.command.commands.fetch.MemeCommand
import bot.toby.command.commands.fetch.MemeEmbeds
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Handles the "Re-roll" button stamped onto a `/meme` reveal. Uses
 * `deferEdit()` + delegation to [MemeCommand.fetch] so the same
 * message is re-rolled in place against the originally-supplied
 * subreddit / time-period / limit.
 */
@Component
class MemeButton @Autowired constructor(
    private val memeCommand: MemeCommand,
) : Button {

    override val name: String get() = MemeEmbeds.BUTTON_NAME
    override val description: String get() = "Re-rolls the /meme result against the same subreddit + filters."

    override val defersReply: Boolean get() = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        val args = MemeEmbeds.decodeReroll(event.componentId) ?: return
        if (requestingUserDto.memePermission != true) return
        memeCommand.fetchAsync(
            hook = event.hook,
            subreddit = args.subreddit,
            timePeriod = args.timePeriod,
            limit = args.limit,
            deleteDelay = deleteDelay,
        )
    }
}
