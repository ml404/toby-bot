package bot.toby.notify

import bot.toby.command.commands.mtg.CubeEmbeds
import common.mtg.CubeCard
import common.mtg.MtgCommandRef
import common.mtg.MtgCurrency
import database.dto.user.CardPriceWatchDto
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData

/**
 * Renders the DM sent when a [CardPriceWatchDto] fires — a card-price-watch
 * alert. The watch is one-shot, so this is a "your target was hit" notice;
 * the footer nudges the user to re-arm with `/mtgprice add`.
 */
object CardPriceAlertBuilder {

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
            .setColor(CubeEmbeds.OK_COLOR)
            .setTitle("📉 Price alert — ${card.name}")
            .setDescription(
                "**${card.name}** has $arrow **${currency.format(currentPrice)}**, " +
                    "crossing your ${watch.directionEnum.name.lowercase()} target of " +
                    "**${currency.format(watch.threshold)}**."
            )
        card.imageUrl?.let { embed.setThumbnail(it) }
        watch.priceAtCreation?.let {
            embed.addField("When you set this", currency.format(it), true)
        }
        embed.addField("Now", currency.format(currentPrice), true)
        embed.setFooter("One-shot alert — use ${MtgCommandRef.PRICEWATCH_ADD} to set another.")
        return MessageCreateBuilder().setEmbeds(embed.build()).build()
    }
}
