package bot.toby.notify

import database.dto.UserPriceTriggerDto
import database.service.EconomyTradeService.TradeOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PriceAlertReceiptBuilderTest {

    private fun trigger(side: UserPriceTriggerDto.Side, amount: Long = 10) = UserPriceTriggerDto(
        id = 42,
        discordId = 1L,
        guildId = 100L,
        thresholdPrice = 100.0,
        priceAtCreation = 120.0,
        side = side.name,
        amount = amount,
        enabled = false,
    )

    private fun renderedText(dm: net.dv8tion.jda.api.utils.messages.MessageCreateData): String {
        val embed = dm.embeds.single()
        val title = embed.title.orEmpty()
        val desc = embed.description.orEmpty()
        val fields = embed.fields.joinToString("\n") { "${it.name}: ${it.value}" }
        return "$title\n$desc\n$fields"
    }

    @Test
    fun `BUY receipt shows subtotal, fee, and total with total = subtotal + fee`() {
        val ok = TradeOutcome.Ok(
            amount = 10L,
            transactedCredits = 1023L,
            newCoins = 145L,
            newCredits = 8977L,
            newPrice = 102.34,
            fee = 11L,
        )
        val dm = PriceAlertReceiptBuilder.buildDm(trigger(UserPriceTriggerDto.Side.BUY), ok)
        val text = renderedText(dm)

        // Subtotal (gross) = transactedCredits - fee = 1012.
        assertTrue(text.contains("**1012**"), "expected gross 1012 wrapped in bold: $text")
        assertTrue(text.contains("**11**"), "expected fee 11 wrapped in bold: $text")
        assertTrue(text.contains("**1023**"), "expected total 1023 wrapped in bold: $text")
        assertTrue(text.contains("jackpot"), "fee destination must be visible: $text")
    }

    @Test
    fun `SELL receipt shows gross, fee, and proceeds with proceeds = gross - fee`() {
        val ok = TradeOutcome.Ok(
            amount = 4L,
            transactedCredits = 396L,
            newCoins = 0L,
            newCredits = 396L,
            newPrice = 99.84,
            fee = 4L,
        )
        val dm = PriceAlertReceiptBuilder.buildDm(trigger(UserPriceTriggerDto.Side.SELL), ok)
        val text = renderedText(dm)

        // Gross = transactedCredits + fee = 400.
        assertTrue(text.contains("**400**"), "expected gross 400 wrapped in bold: $text")
        assertTrue(text.contains("**4**"), "expected fee 4 wrapped in bold: $text")
        assertTrue(text.contains("**396**"), "expected proceeds 396 wrapped in bold: $text")
        assertTrue(text.contains("jackpot"), "fee destination must be visible: $text")
    }

    @Test
    fun `zero-fee receipt does not render a naked plus 0 fee line`() {
        val ok = TradeOutcome.Ok(
            amount = 5L,
            transactedCredits = 500L,
            newCoins = 5L,
            newCredits = 500L,
            newPrice = 100.0,
            fee = 0L,
        )
        val dm = PriceAlertReceiptBuilder.buildDm(trigger(UserPriceTriggerDto.Side.BUY), ok)
        val text = renderedText(dm)

        assertTrue(text.contains("500"), "expected total 500 in: $text")
        assertFalse(text.contains("jackpot"), "zero fee should not mention jackpot: $text")
        assertFalse(text.contains("+ 0"), "zero fee should not render '+ 0' line: $text")
        assertFalse(text.contains("− 0"), "zero fee should not render '− 0' line: $text")
    }

    @Test
    fun `InsufficientCredits embed surfaces fee-inclusive needed amount as-is`() {
        val dm = PriceAlertReceiptBuilder.buildDm(
            trigger(UserPriceTriggerDto.Side.BUY),
            TradeOutcome.InsufficientCredits(needed = 1023L, have = 800L),
        )
        val text = renderedText(dm)
        assertTrue(text.contains("1023"), "needed credits must render verbatim: $text")
        assertTrue(text.contains("800"), "have credits must render verbatim: $text")
    }

    @Test
    fun `InsufficientCoins embed surfaces needed and held coin counts`() {
        val dm = PriceAlertReceiptBuilder.buildDm(
            trigger(UserPriceTriggerDto.Side.SELL),
            TradeOutcome.InsufficientCoins(needed = 10L, have = 3L),
        )
        val text = renderedText(dm)
        assertTrue(text.contains("10"), "needed coins must render: $text")
        assertTrue(text.contains("3"), "held coins must render: $text")
    }

    @Test
    fun `UnknownUser embed explains the trigger is disabled`() {
        val dm = PriceAlertReceiptBuilder.buildDm(
            trigger(UserPriceTriggerDto.Side.BUY),
            TradeOutcome.UnknownUser,
        )
        val text = renderedText(dm)
        assertTrue(text.contains("wallet"), "embed should reference wallet: $text")
        assertTrue(text.contains("disabled") || text.contains("Disabled"),
                "embed should mention disable state: $text")
    }

    @Test
    fun `push payload is non-null for every TradeOutcome variant`() {
        val t = trigger(UserPriceTriggerDto.Side.BUY)
        val cases = listOf(
            TradeOutcome.Ok(10L, 1000L, 10L, 9000L, 100.0, 10L),
            TradeOutcome.InsufficientCredits(needed = 1000L, have = 500L),
            TradeOutcome.InsufficientCoins(needed = 10L, have = 0L),
            TradeOutcome.InvalidAmount,
            TradeOutcome.UnknownUser,
        )
        cases.forEach { outcome ->
            val payload = PriceAlertReceiptBuilder.buildPush(t, outcome)
            assertNotNull(payload, "push payload must satisfy enforceAllSupportedSurfacesWired: $outcome")
            assertEquals("TobyCoin price alert", payload.title)
            assertTrue(payload.body.isNotBlank(), "push body must be non-blank for $outcome")
        }
    }
}
