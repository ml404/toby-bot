package bot.toby.command.commands.misc

import core.command.CommandContext
import database.service.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.*

@Component
class EightBallCommand @Autowired constructor(private val userService: UserService) : MiscCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: database.dto.UserDto, deleteDelay: Int?) {
        val event = ctx.event
        event.deferReply().queue()
        val r = Random()
        val choice = 1 + r.nextInt(20)
        val response = when (choice) {
            1 -> "It is certain"
            2 -> "It is decidedly so"
            3 -> "Without a doubt"
            4 -> "Yes - definitely"
            5 -> "You may rely on it"
            6 -> "As I see it, yes"
            7 -> "Most likely"
            8 -> "Outlook good"
            9 -> "Signs point to yes"
            10 -> "Yes"
            11 -> "Reply hazy, try again"
            12 -> "Ask again later"
            13 -> "Better not tell you now"
            14 -> "Cannot predict now"
            15 -> "Concentrate and ask again"
            16 -> "Don't count on it"
            17 -> "My reply is no"
            18 -> "My sources say no"
            19 -> "Outlook not so good"
            20 -> "Very doubtful"
            else -> "I fucked up, please try again"
        }
        if (requestingUserDto.discordId == TOMS_DISCORD_ID) {
            event.hook.sendMessage("MAGIC 8-BALL SAYS: Don't fucking talk to me.").queue(
                core.command.Command.Companion.invokeDeleteOnMessageResponse(
                    deleteDelay!!
                )
            )
            val socialCredit = requestingUserDto.socialCredit
            val deductedSocialCredit = -5 * choice
            requestingUserDto.socialCredit = socialCredit?.plus(deductedSocialCredit)
            event.hook.sendMessage("Deducted: $deductedSocialCredit social credit.").queue(
                core.command.Command.Companion.invokeDeleteOnMessageResponse(
                    deleteDelay
                )
            )
            userService.updateUser(requestingUserDto)
            return
        }
        event.hook.sendMessage("MAGIC 8-BALL SAYS: ${response}.").queue(
            core.command.Command.Companion.invokeDeleteOnMessageResponse(
                deleteDelay!!
            )
        )
    }

    override val name: String
        get() = "8ball"
    override val description: String
        get() = "Think of a question and let me divine to you an answer!"

    companion object {
        const val TOMS_DISCORD_ID = 312691905030782977L
    }
}
