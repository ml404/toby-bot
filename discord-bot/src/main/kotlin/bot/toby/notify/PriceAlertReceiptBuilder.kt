package bot.toby.notify

import common.notification.PushPayload
import database.dto.economy.UserPriceTriggerDto
import database.dto.economy.UserPriceTriggerDto.Side
import database.service.economy.EconomyTradeService.TradeOutcome
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import java.awt.Color

/**
 * Renders the DM and push payloads for an auto-executed price-alert
 * trade. The trade has already happened by the time we get here —
 * we're a receipt, not a confirmation. No interactive components.
 *
 * Fee handling mirrors the canonical wording in
 * `TobyCoinCommand.describe` so users see the same "X gross + Y fee"
 * breakdown they'd get from a manual `/tobycoin buy|sell`. The fee
 * routes to the per-guild jackpot pool — surfacing that in the embed
 * makes the deduction obvious instead of a mystery line.
 */
object PriceAlertReceiptBuilder {

    private val SUCCESS_COLOR = Color(0x57, 0xF2, 0x87)
    private val FAILURE_COLOR = Color(0xED, 0x42, 0x45)

    fun buildDm(
        trigger: UserPriceTriggerDto,
        outcome: TradeOutcome,
    ): MessageCreateData {
        val side = trigger.sideEnum
        val embed = when (outcome) {
            is TradeOutcome.Ok -> okEmbed(trigger, outcome, side)
            is TradeOutcome.InsufficientCredits -> insufficientCreditsEmbed(trigger, outcome)
            is TradeOutcome.InsufficientCoins -> insufficientCoinsEmbed(trigger, outcome)
            TradeOutcome.InvalidAmount -> failureEmbed(
                trigger,
                "the trade was rejected as invalid (amount ${trigger.amount}). " +
                        "This shouldn't normally happen — please report it."
            )
            TradeOutcome.UnknownUser -> failureEmbed(
                trigger,
                "I couldn't find your wallet in this guild. The trigger has been disabled."
            )
        }
        return MessageCreateBuilder().setEmbeds(embed).build()
    }

    fun buildPush(
        trigger: UserPriceTriggerDto,
        outcome: TradeOutcome,
    ): PushPayload {
        val title = "TobyCoin price alert"
        val body = when (outcome) {
            is TradeOutcome.Ok ->
                "${pastTense(trigger.sideEnum)} ${outcome.amount} TOBY at ${"%.4f".format(outcome.newPrice)}."
            is TradeOutcome.InsufficientCredits ->
                "Trigger #${trigger.id} fired but you lacked credits to BUY."
            is TradeOutcome.InsufficientCoins ->
                "Trigger #${trigger.id} fired but you lacked TOBY to SELL."
            TradeOutcome.InvalidAmount ->
                "Trigger #${trigger.id} fired but the trade was rejected as invalid."
            TradeOutcome.UnknownUser ->
                "Trigger #${trigger.id} fired but your wallet wasn't found."
        }
        return PushPayload(title = title, body = body)
    }

    private fun okEmbed(
        trigger: UserPriceTriggerDto,
        ok: TradeOutcome.Ok,
        side: Side,
    ): net.dv8tion.jda.api.entities.MessageEmbed {
        val isBuy = side == Side.BUY
        val verb = pastTense(side)
        val executionPrice = if (ok.amount > 0L) {
            // Reconstruct from the canonical gross. Both BUY and SELL
            // round the gross at trade time (ceil for BUY, floor for
            // SELL) — re-deriving an "execution price" from
            // gross/amount is a small lie at the sub-credit level, but
            // matches what `/tobycoin buy|sell` users already see and
            // keeps the receipt free of a separate price-per-coin
            // field that would have to be plumbed through TradeOutcome.
            val gross = if (isBuy) ok.transactedCredits - ok.fee
                        else ok.transactedCredits + ok.fee
            gross.toDouble() / ok.amount
        } else 0.0

        val embed = EmbedBuilder()
            .setTitle("Auto-trade executed — Trigger #${trigger.id}")
            .setColor(SUCCESS_COLOR)
            .addField(
                "Target",
                "${"%.4f".format(trigger.thresholdPrice)} (reached, new price " +
                        "${"%.4f".format(ok.newPrice)})",
                false
            )
            .addField(
                "Trade",
                "**$verb ${ok.amount} TOBY** @ ${"%.4f".format(executionPrice)}",
                false
            )

        if (isBuy) {
            val gross = ok.transactedCredits - ok.fee
            val feePart = if (ok.fee > 0L) " + **${ok.fee}** fee (to jackpot)" else ""
            embed.addField(
                "Cost",
                "**${gross}** credits subtotal$feePart = **${ok.transactedCredits}** credits total",
                false
            )
        } else {
            val gross = ok.transactedCredits + ok.fee
            val feePart = if (ok.fee > 0L) " − **${ok.fee}** fee (to jackpot)" else ""
            embed.addField(
                "Proceeds",
                "**${gross}** credits gross$feePart = **${ok.transactedCredits}** credits received",
                false
            )
        }

        embed.addField(
            "New balance",
            "**${ok.newCoins}** TOBY • **${ok.newCredits}** credits",
            false
        )
        embed.setFooter("Trigger one-shot — use /pricealert add to set another.")
        return embed.build()
    }

    private fun insufficientCreditsEmbed(
        trigger: UserPriceTriggerDto,
        outcome: TradeOutcome.InsufficientCredits,
    ) = EmbedBuilder()
        .setTitle("Auto-trade skipped — Trigger #${trigger.id}")
        .setColor(FAILURE_COLOR)
        .setDescription(
            "Target ${"%.4f".format(trigger.thresholdPrice)} was reached, but you didn't " +
                    "have enough credits to BUY ${trigger.amount} TOBY: needed " +
                    "**${outcome.needed}** credits (price + fee), had **${outcome.have}**. " +
                    "No trade made. Trigger disabled."
        )
        .build()

    private fun insufficientCoinsEmbed(
        trigger: UserPriceTriggerDto,
        outcome: TradeOutcome.InsufficientCoins,
    ) = EmbedBuilder()
        .setTitle("Auto-trade skipped — Trigger #${trigger.id}")
        .setColor(FAILURE_COLOR)
        .setDescription(
            "Target ${"%.4f".format(trigger.thresholdPrice)} was reached, but you didn't " +
                    "have enough TOBY to SELL ${trigger.amount}: needed **${outcome.needed}** " +
                    "coins, had **${outcome.have}**. No trade made. Trigger disabled."
        )
        .build()

    private fun failureEmbed(
        trigger: UserPriceTriggerDto,
        reason: String,
    ) = EmbedBuilder()
        .setTitle("Auto-trade failed — Trigger #${trigger.id}")
        .setColor(FAILURE_COLOR)
        .setDescription(
            "Target ${"%.4f".format(trigger.thresholdPrice)} was reached but $reason"
        )
        .build()

    private fun pastTense(side: Side): String = when (side) {
        Side.BUY -> "Bought"
        Side.SELL -> "Sold"
    }
}
