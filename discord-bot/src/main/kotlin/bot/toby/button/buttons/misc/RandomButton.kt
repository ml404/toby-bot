package bot.toby.button.buttons.misc

import bot.toby.command.commands.misc.RandomCommand
import bot.toby.command.commands.misc.RandomEmbeds
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Handles the "Pick another" button stamped onto a `/random` reveal.
 * Uses `deferEdit()` + delegation to [RandomCommand.pick] so the same
 * message is re-spun in place against the previously-supplied options.
 */
@Component
class RandomButton @Autowired constructor(
    private val randomCommand: RandomCommand,
) : Button {

    override val name: String get() = RandomEmbeds.BUTTON_NAME
    override val description: String get() = "Re-picks a winner from the original /random options."

    override val defersReply: Boolean get() = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        val options = RandomEmbeds.decodeOptions(event.componentId)
        randomCommand.pick(event.hook, options, event.user.effectiveName, deleteDelay)
    }
}
