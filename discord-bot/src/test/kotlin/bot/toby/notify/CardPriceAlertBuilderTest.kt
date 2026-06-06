package bot.toby.notify

import common.mtg.CubeCard
import common.mtg.MtgCurrency
import database.dto.user.CardPriceWatchDto
import database.dto.user.CardPriceWatchDto.Direction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CardPriceAlertBuilderTest {

    @Test
    fun `below alert names the card, the crossing and the target in the right currency`() {
        val watch = CardPriceWatchDto(
            id = 3, discordId = 7, cardName = "Ragavan", currency = "usd",
            direction = Direction.BELOW.name, threshold = 30.0, priceAtCreation = 45.0,
        )
        val card = CubeCard("Ragavan, Nimble Pilferer", priceUsd = "20.00", imageUrl = "https://img/r.jpg")
        val dm = CardPriceAlertBuilder.buildDm(watch, card, MtgCurrency.USD, 20.0)
        val embed = dm.embeds.first()
        assertTrue(embed.title!!.contains("Ragavan, Nimble Pilferer"))
        val desc = embed.description!!
        assertTrue(desc.contains("dropped to"), desc)
        assertTrue(desc.contains("$20.00"), desc)
        assertTrue(desc.contains("$30.00"), desc)
    }

    @Test
    fun `above alert uses the risen wording and the chosen currency symbol`() {
        val watch = CardPriceWatchDto(
            id = 4, discordId = 7, cardName = "Mox", currency = "eur",
            direction = Direction.ABOVE.name, threshold = 100.0,
        )
        val card = CubeCard("Mox Diamond", priceEur = "120.00")
        val dm = CardPriceAlertBuilder.buildDm(watch, card, MtgCurrency.EUR, 120.0)
        val desc = dm.embeds.first().description!!
        assertTrue(desc.contains("risen to"), desc)
        assertTrue(desc.contains("€120.00"), desc)
        assertTrue(desc.contains("€100.00"), desc)
    }
}
