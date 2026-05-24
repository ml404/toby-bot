package bot.toby.button.buttons.misc

import bot.toby.command.commands.misc.EightBallCommand
import bot.toby.command.commands.misc.EightBallEmbeds
import core.button.Button
import core.button.ButtonContext
import database.dto.user.UserDto
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Handles the "Ask again" button stamped onto a revealed 8-ball reply.
 * Uses `deferEdit()` so the original message can be re-shaken in place
 * via [EightBallCommand.ask] — no ephemeral followups, no scroll noise.
 */
@Component
class EightBallButton @Autowired constructor(
    private val eightBallCommand: EightBallCommand,
) : Button {

    override val name: String get() = EightBallEmbeds.BUTTON_NAME
    override val description: String get() = "Re-rolls the Magic 8-Ball in place."

    // The manager would otherwise send an ephemeral defer + followup,
    // which doesn't make sense for an in-place re-roll.
    override val defersReply: Boolean get() = false

    override fun handle(ctx: ButtonContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferEdit().queue()
        eightBallCommand.ask(event.hook, requestingUserDto, event.user.effectiveName, deleteDelay)
    }
}
