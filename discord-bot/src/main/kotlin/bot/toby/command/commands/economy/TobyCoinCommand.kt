package bot.toby.command.commands.economy

import bot.toby.economy.TobyCoinChartRenderer
import common.economy.Coin
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.Command.Companion.replyAndDelete
import core.command.Command.Companion.replyEmbedAndDelete
import core.command.CommandContext
import database.dto.economy.TobyCoinMarketDto
import database.dto.user.UserDto
import database.service.economy.EconomyTradeService
import database.service.economy.EconomyTradeService.TradeOutcome
import database.service.economy.TobyCoinMarketService
import database.service.economy.UserCoinHoldingService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.utils.FileUpload
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.awt.Color
import java.time.Duration
import java.time.Instant

@Component
class TobyCoinCommand @Autowired constructor(
    private val marketService: TobyCoinMarketService,
    private val tradeService: EconomyTradeService,
    private val holdingService: UserCoinHoldingService,
    private val chartRenderer: TobyCoinChartRenderer
) : EconomyCommand {

    override val name: String = "tobycoin"
    override val description: String =
        "Trade social credit for the server's fake cryptocurrencies — pick your risk appetite."

    companion object {
        private const val OPT_AMOUNT = "amount"
        private const val OPT_WINDOW = "window"
        private const val OPT_COIN = "coin"
        private const val WINDOW_1D = "1d"
        private const val WINDOW_5D = "5d"
        private const val WINDOW_1MO = "1mo"
        private const val WINDOW_3MO = "3mo"
        private const val WINDOW_1Y = "1y"
        private const val WINDOW_ALL = "all"

        /** A fresh `coin` option, listing every coin with its risk label. */
        private fun coinOption(description: String): OptionData =
            OptionData(OptionType.STRING, OPT_COIN, description, false).apply {
                Coin.entries.forEach { addChoice("${it.displayName} (${it.riskLabel})", it.symbol) }
            }
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData("markets", "List every coin, its price and how wild it is."),
        SubcommandData("price", "Show the current price for a coin (defaults to TOBY).")
            .addOptions(coinOption("Which coin (defaults to TOBY)")),
        SubcommandData("balance", "Show your full coin portfolio and its value."),
        SubcommandData("buy", "Buy a coin with social credit.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_AMOUNT, "Number of coins to buy", true)
                    .setMinValue(1),
                coinOption("Which coin to buy (defaults to TOBY)")
            ),
        SubcommandData("sell", "Sell a coin for social credit.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_AMOUNT, "Number of coins to sell", true)
                    .setMinValue(1),
                coinOption("Which coin to sell (defaults to TOBY)")
            ),
        SubcommandData("chart", "Render a coin's market chart.")
            .addOptions(
                coinOption("Which coin to chart (defaults to TOBY)"),
                OptionData(OptionType.STRING, OPT_WINDOW, "Time window (defaults to 1d)", false)
                    .addChoice("1 day", WINDOW_1D)
                    .addChoice("5 days", WINDOW_5D)
                    .addChoice("1 month", WINDOW_1MO)
                    .addChoice("3 months", WINDOW_3MO)
                    .addChoice("1 year", WINDOW_1Y)
                    .addChoice("All time", WINDOW_ALL)
            )
    )

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val event = ctx.event

        val guild = event.guild ?: run {
            reply(event, "This command can only be used in a server.", deleteDelay); return
        }
        val coin = resolveCoin(event)

        when (event.subcommandName) {
            "markets" -> handleMarkets(event, guild.name, deleteDelay)
            "price" -> handlePrice(event, guild.name, coin, deleteDelay)
            "balance" -> handleBalance(event, requestingUserDto, deleteDelay)
            "buy" -> handleBuy(event, requestingUserDto, coin, deleteDelay)
            "sell" -> handleSell(event, requestingUserDto, coin, deleteDelay)
            "chart" -> handleChart(event, guild.name, coin, deleteDelay)
            else -> reply(event, "Unknown subcommand.", deleteDelay)
        }
    }

    private fun resolveCoin(event: SlashCommandInteractionEvent): Coin =
        Coin.fromSymbol(event.getOption(OPT_COIN)?.asString)

    private fun handleMarkets(
        event: SlashCommandInteractionEvent,
        guildName: String,
        deleteDelay: Int
    ) {
        val embed = EmbedBuilder()
            .setTitle("Markets • $guildName")
            .setDescription("Pick your risk appetite. Buy/sell with `/tobycoin buy coin:<name>`.")
            .setColor(Color(0x57, 0xF2, 0x87))

        Coin.entries.forEach { coin ->
            val market = tradeService.loadOrCreateMarket(event.guild!!.idLong, coin)
            val since = Instant.now().minus(Duration.ofDays(1))
            val dayAgo = marketService.listHistory(market.guildId, since, coin).firstOrNull()?.price
            val change = dayAgo?.let { ((market.price - it) / it) * 100.0 }
            val changeText = change?.let { "%+.2f%% (24h)".format(it) } ?: "n/a (24h)"
            embed.addField(
                "${coin.symbol} — ${coin.displayName} · ${coin.riskLabel}",
                "%.2f credits/coin · $changeText\n_${coin.blurb}_".format(market.price),
                false
            )
        }
        event.hook.replyEmbedAndDelete(embed.build(), deleteDelay)
    }

    private fun handlePrice(
        event: SlashCommandInteractionEvent,
        guildName: String,
        coin: Coin,
        deleteDelay: Int
    ) {
        val market = tradeService.loadOrCreateMarket(event.guild!!.idLong, coin)
        val since = Instant.now().minus(Duration.ofDays(1))
        val dayAgo = marketService.listHistory(market.guildId, since, coin).firstOrNull()?.price
        val change = dayAgo?.let { ((market.price - it) / it) * 100.0 }
        val changeText = change?.let { "%+.2f%% (24h)".format(it) } ?: "n/a (24h)"

        val embed = EmbedBuilder()
            .setTitle("${coin.symbol} • $guildName")
            .setDescription("${coin.displayName} · ${coin.riskLabel}")
            .addField("Price", "%.2f credits / coin".format(market.price), true)
            .addField("24h change", changeText, true)
            .addField("Last tick", "<t:${market.lastTickAt.epochSecond}:R>", true)
            .addField(
                "Fee",
                "1% on every buy & sell — feeds the server jackpot.",
                false
            )
            .setColor(priceColor(change))
            .setFooter("Try /tobycoin chart coin:${coin.symbol} to see the market")
            .build()
        event.hook.replyEmbedAndDelete(embed, deleteDelay)
    }

    private fun handleBalance(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        deleteDelay: Int
    ) {
        val guildId = userDto.guildId
        val credits = userDto.socialCredit ?: 0L

        val embed = EmbedBuilder()
            .setTitle("Your coin portfolio")
            .setColor(Color(0x57, 0xF2, 0x87))

        var totalValue = 0L
        Coin.entries.forEach { coin ->
            val coins = if (coin == Coin.TOBY) userDto.tobyCoins
                        else holdingService.getAmount(userDto.discordId, guildId, coin)
            // Skip coins the user doesn't hold, except always show TOBY so
            // the wallet never looks empty for the flagship currency.
            if (coins == 0L && coin != Coin.TOBY) return@forEach
            val price = marketService.getMarket(guildId, coin)?.price ?: coin.initialPrice
            val value = (coins.toDouble() * price).toLong()
            totalValue += value
            embed.addField(
                "${coin.symbol} · ${coin.riskLabel}",
                "$coins coins · worth $value credits\n_@ %.2f credits/coin_".format(price),
                true
            )
        }
        embed.addField("Social credit", "$credits credits", false)
        embed.setFooter("Total coin value: $totalValue credits")
        event.hook.replyEmbedAndDelete(embed.build(), deleteDelay)
    }

    private fun handleBuy(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        coin: Coin,
        deleteDelay: Int
    ) {
        val amount = event.getOption(OPT_AMOUNT)?.asLong ?: run {
            reply(event, "You must specify an amount.", deleteDelay); return
        }
        val outcome = tradeService.buy(userDto.discordId, userDto.guildId, amount, coin = coin)
        reply(event, describe(outcome, "Bought", amount, coin, isBuy = true), deleteDelay)
    }

    private fun handleSell(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        coin: Coin,
        deleteDelay: Int
    ) {
        val amount = event.getOption(OPT_AMOUNT)?.asLong ?: run {
            reply(event, "You must specify an amount.", deleteDelay); return
        }
        val outcome = tradeService.sell(userDto.discordId, userDto.guildId, amount, coin = coin)
        reply(event, describe(outcome, "Sold", amount, coin, isBuy = false), deleteDelay)
    }

    private fun handleChart(
        event: SlashCommandInteractionEvent,
        guildName: String,
        coin: Coin,
        deleteDelay: Int
    ) {
        val market = tradeService.loadOrCreateMarket(event.guild!!.idLong, coin)
        val window = event.getOption(OPT_WINDOW)?.asString ?: WINDOW_1D
        val now = Instant.now()
        val points = when (window) {
            WINDOW_ALL -> marketService.listAllHistory(market.guildId, coin)
            WINDOW_5D -> marketService.listHistory(market.guildId, now.minus(Duration.ofDays(5)), coin)
            WINDOW_1MO -> marketService.listHistory(market.guildId, now.minus(Duration.ofDays(30)), coin)
            WINDOW_3MO -> marketService.listHistory(market.guildId, now.minus(Duration.ofDays(90)), coin)
            WINDOW_1Y -> marketService.listHistory(market.guildId, now.minus(Duration.ofDays(365)), coin)
            else -> marketService.listHistory(market.guildId, now.minus(Duration.ofDays(1)), coin)
        }
        if (points.size < 2) {
            reply(
                event,
                "Not enough price history for a chart yet — come back after a tick or two.",
                deleteDelay
            ); return
        }

        val png = chartRenderer.renderPng(guildName, points, coin)
        val windowLabel = when (window) {
            WINDOW_5D -> "5 days"
            WINDOW_1MO -> "1 month"
            WINDOW_3MO -> "3 months"
            WINDOW_1Y -> "1 year"
            WINDOW_ALL -> "all time"
            else -> "1 day"
        }
        val embed = EmbedBuilder()
            .setTitle("${coin.symbol} market chart — $windowLabel")
            .setDescription("Current price: **%.2f** credits / coin".format(market.price))
            .setImage("attachment://tobycoin-chart.png")
            .setColor(Color(0x57, 0xF2, 0x87))
            .build()
        event.hook.sendMessageEmbeds(embed)
            .addFiles(FileUpload.fromData(png, "tobycoin-chart.png"))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun describe(
        outcome: TradeOutcome,
        verb: String,
        amount: Long,
        coin: Coin,
        isBuy: Boolean
    ): String = when (outcome) {
        is TradeOutcome.Ok -> {
            val breakdown = if (outcome.fee > 0L) {
                // Buyers pay gross + fee; sellers receive gross − fee.
                val gross = if (isBuy) outcome.transactedCredits - outcome.fee
                            else outcome.transactedCredits + outcome.fee
                val sign = if (isBuy) "+" else "−"
                " ($gross gross $sign ${outcome.fee} fee, 1%)"
            } else ""
            // Format the price up front so the message stays plain
            // interpolation. Running String.format on the composed
            // sentence used to choke on the literal `%)` inside `breakdown`
            // (UnknownFormatConversionException: Conversion = ')').
            val newPrice = "%.2f".format(outcome.newPrice)
            "$verb **${outcome.amount} ${coin.symbol}** for ${outcome.transactedCredits} credits$breakdown. " +
                "New price: $newPrice. You now hold ${outcome.newCoins} ${coin.symbol} and ${outcome.newCredits} credits."
        }
        is TradeOutcome.InsufficientCredits ->
            "You need ${outcome.needed} credits for this trade (price + 1% fee) but only have ${outcome.have}."
        is TradeOutcome.InsufficientCoins ->
            "You need ${outcome.needed} ${coin.symbol} for this trade but only have ${outcome.have}."
        TradeOutcome.InvalidAmount -> "Amount must be a positive number. You asked for $amount."
        TradeOutcome.UnknownUser ->
            "Could not find your user record. Try running another command first so TobyBot registers you."
    }

    private fun priceColor(change24h: Double?): Color = when {
        change24h == null -> Color(0xB9, 0xBB, 0xBE)
        change24h >= 0 -> Color(0x57, 0xF2, 0x87)
        else -> Color(0xED, 0x42, 0x45)
    }

    private fun reply(event: SlashCommandInteractionEvent, message: String, deleteDelay: Int) {
        event.hook.replyAndDelete(message, deleteDelay)
    }
}
