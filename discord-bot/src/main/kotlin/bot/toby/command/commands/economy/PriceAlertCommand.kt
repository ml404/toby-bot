package bot.toby.command.commands.economy

import common.notification.NotificationChannelKind
import common.notification.Surface
import core.command.Command.Companion.replyEphemeralAndDelete
import core.command.Command.Companion.replyEphemeralEmbedAndDelete
import core.command.CommandContext
import database.dto.UserDto
import database.dto.UserPriceTriggerDto
import database.service.EconomyTradeService
import database.service.UserNotificationPrefService
import database.service.UserPriceTriggerService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color
import kotlin.math.abs

@Component
class PriceAlertCommand @Autowired constructor(
    private val triggerService: UserPriceTriggerService,
    private val tradeService: EconomyTradeService,
    private val prefService: UserNotificationPrefService,
) : EconomyCommand {

    override val name: String = "pricealert"
    override val description: String =
        "Set a TobyCoin price target that auto-executes a buy/sell when reached."

    companion object {
        private const val OPT_PRICE = "price"
        private const val OPT_SIDE = "side"
        private const val OPT_AMOUNT = "amount"
        private const val OPT_ID = "id"

        // Same precision as the threshold column (NUMERIC(20,6)).
        // Rejecting threshold == currentPrice (rounded to 4dp) avoids
        // the "armed at parity" edge case where any next-tick movement
        // satisfies the target.
        private const val PARITY_EPSILON = 1e-4
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData("add", "Register a target-price auto-trade.")
            .addOptions(
                OptionData(OptionType.NUMBER, OPT_PRICE, "Target price (credits per coin)", true)
                    .setMinValue(0.01),
                OptionData(OptionType.STRING, OPT_SIDE, "BUY or SELL when target is reached", true)
                    .addChoice("BUY", UserPriceTriggerDto.Side.BUY.name)
                    .addChoice("SELL", UserPriceTriggerDto.Side.SELL.name),
                OptionData(OptionType.INTEGER, OPT_AMOUNT, "Coins to trade", true)
                    .setMinValue(1)
            ),
        SubcommandData("list", "Show your active price-alert triggers."),
        SubcommandData("remove", "Delete one of your triggers.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_ID, "Trigger id (from /pricealert list)", true)
                    .setMinValue(1)
            )
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event
        event.deferReply(true).queue()

        val guild = event.guild ?: run {
            event.hook.replyEphemeralAndDelete(
                "This command can only be used in a server.", deleteDelay
            ); return
        }
        val guildId = guild.idLong
        val discordId = event.user.idLong

        when (event.subcommandName) {
            "add" -> handleAdd(event, discordId, guildId, deleteDelay)
            "list" -> handleList(event, discordId, guildId, deleteDelay)
            "remove" -> handleRemove(event, discordId, deleteDelay)
            else -> event.hook.replyEphemeralAndDelete("Unknown subcommand.", deleteDelay)
        }
    }

    private fun handleAdd(
        event: SlashCommandInteractionEvent,
        discordId: Long,
        guildId: Long,
        deleteDelay: Int,
    ) {
        val threshold = event.getOption(OPT_PRICE)?.asDouble ?: run {
            event.hook.replyEphemeralAndDelete("Missing price.", deleteDelay); return
        }
        val sideName = event.getOption(OPT_SIDE)?.asString ?: run {
            event.hook.replyEphemeralAndDelete("Missing side.", deleteDelay); return
        }
        val amount = event.getOption(OPT_AMOUNT)?.asLong ?: run {
            event.hook.replyEphemeralAndDelete("Missing amount.", deleteDelay); return
        }
        val side = runCatching { UserPriceTriggerDto.Side.valueOf(sideName) }
            .getOrElse {
                event.hook.replyEphemeralAndDelete(
                    "Side must be BUY or SELL.", deleteDelay
                ); return
            }

        val market = tradeService.loadOrCreateMarket(guildId)
        val currentPrice = market.price

        if (abs(threshold - currentPrice) < PARITY_EPSILON) {
            event.hook.replyEphemeralAndDelete(
                "Threshold (${"%.4f".format(threshold)}) is essentially the current price " +
                        "(${"%.4f".format(currentPrice)}). Pick a target meaningfully above or " +
                        "below the current price so the direction is unambiguous.",
                deleteDelay
            )
            return
        }

        val trigger = triggerService.create(
            discordId = discordId,
            guildId = guildId,
            threshold = threshold,
            priceAtCreation = currentPrice,
            side = side,
            amount = amount,
        )

        // Auto-enable PRICE_ALERT DM so the receipt is actually
        // delivered. Per the approved plan: convenience beats silent
        // dropping. The user can flip it off later via /notify.
        val wasOptedIn = prefService.isOptedIn(
            discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.DM
        )
        if (!wasOptedIn) {
            prefService.setPref(
                discordId, guildId,
                NotificationChannelKind.PRICE_ALERT, Surface.DM, optIn = true,
            )
        }

        val direction = if (threshold < currentPrice) "drop" else "rise"
        val movePct = abs(threshold - currentPrice) / currentPrice * 100.0
        val description = buildString {
            append("Trigger **#${trigger.id}** set. Current price ")
            append("**${"%.4f".format(currentPrice)}**; when TobyCoin reaches ")
            append("**${"%.4f".format(threshold)}** (a $direction of ")
            append("${"%.2f".format(movePct)}%) you'll auto-**${side.name} ${amount}** ")
            append("and get a DM receipt.")
            if (!wasOptedIn) {
                append("\n\n_PRICE_ALERT DMs were off; I enabled them for you so the " +
                        "receipt can reach you._")
            }
        }

        val embed = EmbedBuilder()
            .setTitle("Price alert created")
            .setDescription(description)
            .setColor(Color(0x57, 0xF2, 0x87))
            .build()
        event.hook.replyEphemeralEmbedAndDelete(embed, deleteDelay)
    }

    private fun handleList(
        event: SlashCommandInteractionEvent,
        discordId: Long,
        guildId: Long,
        deleteDelay: Int,
    ) {
        val triggers = triggerService.listForUser(discordId, guildId)
        if (triggers.isEmpty()) {
            event.hook.replyEphemeralAndDelete(
                "You have no price-alert triggers. Use `/pricealert add` to create one.",
                deleteDelay
            )
            return
        }

        val currentPrice = tradeService.loadOrCreateMarket(guildId).price
        val embed = EmbedBuilder()
            .setTitle("Your price-alert triggers")
            .setDescription("Current TobyCoin price: **${"%.4f".format(currentPrice)}**")

        triggers.forEach { t ->
            val status = when {
                !t.enabled && t.firedAt != null -> "fired <t:${t.firedAt!!.epochSecond}:R>"
                !t.enabled -> "disabled"
                else -> "armed (waiting)"
            }
            val direction = if (t.thresholdPrice < t.priceAtCreation) "↓ drop to" else "↑ rise to"
            embed.addField(
                "#${t.id} • ${t.side} ${t.amount}",
                "$direction **${"%.4f".format(t.thresholdPrice)}** " +
                        "(created at ${"%.4f".format(t.priceAtCreation)}) — $status",
                false
            )
        }
        event.hook.replyEphemeralEmbedAndDelete(embed.build(), deleteDelay)
    }

    private fun handleRemove(
        event: SlashCommandInteractionEvent,
        discordId: Long,
        deleteDelay: Int,
    ) {
        val id = event.getOption(OPT_ID)?.asLong ?: run {
            event.hook.replyEphemeralAndDelete("Missing id.", deleteDelay); return
        }
        val removed = triggerService.remove(id, discordId)
        if (removed) {
            event.hook.replyEphemeralAndDelete("Trigger #$id removed.", deleteDelay)
        } else {
            event.hook.replyEphemeralAndDelete(
                "No trigger #$id found that you own.", deleteDelay
            )
        }
    }
}
