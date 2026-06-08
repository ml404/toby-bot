package bot.toby.command.commands.misc

import database.service.social.LoginStreakService.ClaimResult
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color

/**
 * Shared renderer for a daily-claim outcome, used by both [DailyCommand]
 * and the install wizard's one-click "Claim daily" launcher
 * ([bot.toby.install.button.InstallClaimDailyButton]) so the slash command
 * and the button stay pixel-identical.
 */
object DailyEmbeds {

    fun claimResult(result: ClaimResult): MessageEmbed = when (result) {
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
}
