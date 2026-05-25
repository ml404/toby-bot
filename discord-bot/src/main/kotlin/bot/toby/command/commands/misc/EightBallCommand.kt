package bot.toby.command.commands.misc

import core.command.CommandContext
import database.dto.user.UserDto
import database.service.user.UserService
import net.dv8tion.jda.api.interactions.InteractionHook
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.Random
import java.util.concurrent.TimeUnit

@Component
class EightBallCommand @Autowired constructor(private val userService: UserService) : MiscCommand {

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        ask(event.hook, requestingUserDto, event.user.effectiveName, deleteDelay)
    }

    /**
     * Shared entry point so the `EightBallButton` re-ask flow renders the
     * same two-stage shake→reveal as a fresh `/8ball` invocation. Caller
     * is responsible for acknowledging the interaction first
     * (`deferReply()` for slash, `deferEdit()` for the button) so the
     * hook is ready to edit the original message.
     */
    fun ask(hook: InteractionHook, requestingUserDto: UserDto, askedBy: String, deleteDelay: Int) {
        val choice = 1 + Random().nextInt(20)
        val response = RESPONSES[choice - 1]

        if (requestingUserDto.discordId == TOMS_DISCORD_ID) {
            val deducted = -5 * choice
            requestingUserDto.socialCredit = (requestingUserDto.socialCredit ?: 0L) + deducted
            userService.updateUser(requestingUserDto)
            hook.editOriginalEmbeds(listOf(EightBallEmbeds.tomEmbed(deducted))).queue {
                if (deleteDelay > 0) it.delete().queueAfter(deleteDelay.toLong(), TimeUnit.SECONDS)
            }
            return
        }

        // Collection overload of editOriginalEmbeds rather than the
        // varargs one — mockk's vararg matchers don't play nicely with
        // the JDA default-method dispatch, and this is exactly one
        // embed each call anyway.
        hook.editOriginalEmbeds(listOf(EightBallEmbeds.shakeEmbed())).queue()
        hook.editOriginalEmbeds(listOf(EightBallEmbeds.answerEmbed(response, askedBy)))
            .setComponents(EightBallEmbeds.askAgainRow())
            .queueAfter(REVEAL_DELAY_MS, TimeUnit.MILLISECONDS) {
                if (deleteDelay > 0) it.delete().queueAfter(deleteDelay.toLong(), TimeUnit.SECONDS)
            }
    }

    override val name: String get() = "8ball"
    override val description: String get() = "Think of a question and let me divine to you an answer!"

    companion object {
        // A Discord snowflake, not a credential. The shape trips Qodana's
        // "discord-client-id" hardcoded-password heuristic.
        @Suppress("HardcodedPasswords")
        const val TOMS_DISCORD_ID = 312691905030782977L

        const val REVEAL_DELAY_MS = 1200L

        val RESPONSES = listOf(
            "It is certain",
            "It is decidedly so",
            "Without a doubt",
            "Yes - definitely",
            "You may rely on it",
            "As I see it, yes",
            "Most likely",
            "Outlook good",
            "Signs point to yes",
            "Yes",
            "Reply hazy, try again",
            "Ask again later",
            "Better not tell you now",
            "Cannot predict now",
            "Concentrate and ask again",
            "Don't count on it",
            "My reply is no",
            "My sources say no",
            "Outlook not so good",
            "Very doubtful",
        )
    }
}
