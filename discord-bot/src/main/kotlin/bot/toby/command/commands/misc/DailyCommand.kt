package bot.toby.command.commands.misc

import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import core.command.CommandContext
import database.dto.user.UserDto
import database.service.social.LoginStreakService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color

@Component
class DailyCommand @Autowired constructor(
    private val loginStreakService: LoginStreakService
) : MiscCommand {

    override val name: String = "daily"
    override val description: String =
        "Claim today's daily reward. Keep claiming on consecutive days to grow your streak."

    override val ephemeral: Boolean = true

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        val guild = event.guild ?: run {
            event.hook.replyEphemeralEmbedAndDelete(
                errorEmbed("This command can only be used in a server."),
                deleteDelay
            )
            return
        }
        val result = loginStreakService.claim(
            discordId = event.user.idLong,
            guildId = guild.idLong,
            channelId = event.channel.idLong
        )
        event.hook.replyEphemeralEmbedAndDelete(DailyEmbeds.claimResult(result), deleteDelay)
    }

    private fun errorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("Couldn't claim")
        .setDescription(message)
        .setColor(Color(0xEF4444))
        .build()
}
