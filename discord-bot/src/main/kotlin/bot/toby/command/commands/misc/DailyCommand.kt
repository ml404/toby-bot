package bot.toby.command.commands.misc

import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import core.command.CommandContext
import database.dto.UserDto
import database.service.social.LoginStreakService
import database.service.social.LoginStreakService.ClaimResult
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

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()
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
        event.hook.replyEphemeralEmbedAndDelete(buildEmbed(result), deleteDelay)
    }

    private fun buildEmbed(result: ClaimResult): MessageEmbed = when (result) {
        is ClaimResult.Granted -> {
            val title = if (result.isNewBest) "🔥 New personal best — Day ${result.currentStreak}!"
                        else "✅ Daily claimed — Day ${result.currentStreak}"
            EmbedBuilder()
                .setTitle(title)
                .setDescription(buildString {
                    append("**+").append(result.xpGranted).append(" XP**")
                    if (result.creditsGranted > 0) {
                        append("  ·  **+").append(result.creditsGranted).append(" credits**")
                    }
                    append('\n')
                    append("Current streak: ").append(result.currentStreak)
                    append("  ·  Best: ").append(result.longestStreak)
                    append("\n\nCome back tomorrow to keep the streak alive.")
                })
                .setColor(Color(0x4ADE80))
                .build()
        }
        is ClaimResult.AlreadyClaimed -> EmbedBuilder()
            .setTitle("Already claimed today")
            .setDescription(
                "You're at Day **${result.currentStreak}** (best: ${result.longestStreak}). " +
                "Come back after midnight UTC to keep the streak going."
            )
            .setColor(Color(0x60A5FA))
            .build()
    }

    private fun errorEmbed(message: String): MessageEmbed = EmbedBuilder()
        .setTitle("Couldn't claim")
        .setDescription(message)
        .setColor(Color(0xEF4444))
        .build()
}
