package bot.toby.command.commands.game.casino.coinflip

import bot.toby.command.commands.game.WagerCommandColors
import bot.toby.command.commands.game.WagerCommandEmbeds
import bot.toby.command.commands.game.WagerCommandFailure
import database.service.casino.coinflip.CoinflipService.FlipOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * Shared renderer for a [FlipOutcome], used by both [CoinflipCommand] and
 * the install wizard's one-click "Flip a coin" launcher
 * ([bot.toby.install.button.InstallQuickFlipButton]) so the slash command
 * and the button can't drift on copy or colours.
 */
object CoinflipEmbeds {

    const val TITLE: String = "🪙 Coinflip"

    fun outcome(outcome: FlipOutcome): MessageEmbed = when (outcome) {
        is FlipOutcome.Win -> EmbedBuilder()
            .setTitle("🪙 ${outcome.landed.display}!")
            .setDescription("You called **${outcome.predicted.display}** and won **+${outcome.net} credits**.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.WIN)
            .build()

        is FlipOutcome.Lose -> EmbedBuilder()
            .setTitle("🪙 ${outcome.landed.display}!")
            .setDescription("You called **${outcome.predicted.display}**. Lost **${outcome.stake} credits**.")
            .addField("New balance", "${outcome.newBalance} credits", true)
            .setColor(WagerCommandColors.LOSE)
            .build()

        is FlipOutcome.InsufficientCredits -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCredits(outcome.stake, outcome.have)
        )
        is FlipOutcome.InsufficientCoinsForTopUp -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InsufficientCoinsForTopUp(outcome.needed, outcome.have)
        )
        is FlipOutcome.InvalidStake -> WagerCommandEmbeds.failureEmbed(
            TITLE, WagerCommandFailure.InvalidStake(outcome.min, outcome.max)
        )
        FlipOutcome.UnknownUser -> WagerCommandEmbeds.failureEmbed(TITLE, WagerCommandFailure.UnknownUser)
    }
}
