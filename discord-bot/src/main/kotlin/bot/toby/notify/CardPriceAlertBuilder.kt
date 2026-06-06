package bot.toby.notify

import common.mtg.CubeCard
import common.mtg.MtgCommandRef
import common.mtg.MtgCurrency
import database.dto.user.CardPriceWatchDto
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import java.awt.Color

/**
 * Renders the DM sent when a [CardPriceWatchDto] fires — a card-price-watch
 * alert. The watch is one-shot, so this is a "your target was hit" notice;
 * the footer nudges the user to re-arm with `/mtgprice add`.
 */
object CardPriceAlertBuilder {

    private val GOLD = Color(199, 161, 79)

    fun buildDm(
        watch: CardPriceWatchDto,
        card: CubeCard,
        currency: MtgCurrency,
        currentPrice: Double,
    ): MessageCreateData {
        val arrow = when (watch.directionEnum) {
            CardPriceWatchDto.Direction.BELOW -> "dropped to"
            CardPriceWatchDto.Direction.ABOVE -> "risen to"
        }
        val embed = EmbedBuilder()
            .setColor(GOLD)
            .setTitle("📉 Price alert — ${card.name}")
            .setDescription(
                "**${card.name}** has $arrow **${money(currentPrice, currency)}**, " +
                    "crossing your ${watch.directionEnum.name.lowercase()} target of " +
                    "**${money(watch.threshold, currency)}**."
            )
        card.imageUrl?.let { embed.setThumbnail(it) }
        watch.priceAtCreation?.let {
            embed.addField("When you set this", money(it, currency), true)
        }
        embed.addField("Now", money(currentPrice, currency), true)
        embed.setFooter("One-shot alert — use ${MtgCommandRef.PRICEWATCH_ADD} to set another.")
        return MessageCreateBuilder().setEmbeds(embed.build()).build()
    }

    private fun money(amount: Double, currency: MtgCurrency): String =
        "${currency.symbol}${"%.2f".format(amount)}${currency.suffix}"
}
