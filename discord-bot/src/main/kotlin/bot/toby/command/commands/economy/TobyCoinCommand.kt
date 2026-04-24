package bot.toby.command.commands.economy

import bot.toby.economy.TobyCoinChartRenderer
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.TobyCoinMarketDto
import database.dto.UserDto
import database.service.EconomyTradeService
import database.service.EconomyTradeService.TradeOutcome
import database.service.TobyCoinMarketService
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
    private val chartRenderer: TobyCoinChartRenderer
) : EconomyCommand {

    override val name: String = "tobycoin"
    override val description: String =
        "Trade social credit for Toby Coin, the official fake cryptocurrency of this server."

    companion object {
        private const val OPT_AMOUNT = "amount"
        private const val OPT_WINDOW = "window"
        private const val WINDOW_1D = "1d"
        private const val WINDOW_5D = "5d"
        private const val WINDOW_1MO = "1mo"
        private const val WINDOW_3MO = "3mo"
        private const val WINDOW_1Y = "1y"
        private const val WINDOW_ALL = "all"
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData("price", "Show the current Toby Coin price for this server."),
        SubcommandData("balance", "Show your Toby Coin balance and portfolio value."),
        SubcommandData("buy", "Buy Toby Coin with social credit.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_AMOUNT, "Number of coins to buy", true)
                    .setMinValue(1)
            ),
        SubcommandData("sell", "Sell Toby Coin for social credit.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_AMOUNT, "Number of coins to sell", true)
                    .setMinValue(1)
            ),
        SubcommandData("chart", "Render the Toby Coin market chart for this server.")
            .addOptions(
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
        event.deferReply().queue()

        val guild = event.guild ?: run {
            reply(event, "This command can only be used in a server.", deleteDelay); return
        }
        val market = tradeService.loadOrCreateMarket(guild.idLong)

        when (event.subcommandName) {
            "price" -> handlePrice(event, guild.name, market, deleteDelay)
            "balance" -> handleBalance(event, requestingUserDto, market, deleteDelay)
            "buy" -> handleBuy(event, requestingUserDto, deleteDelay)
            "sell" -> handleSell(event, requestingUserDto, deleteDelay)
            "chart" -> handleChart(event, guild.name, market, deleteDelay)
            else -> reply(event, "Unknown subcommand.", deleteDelay)
        }
    }

    private fun handlePrice(
        event: SlashCommandInteractionEvent,
        guildName: String,
        market: TobyCoinMarketDto,
        deleteDelay: Int
    ) {
        val since = Instant.now().minus(Duration.ofDays(1))
        val dayAgo = marketService.listHistory(market.guildId, since).firstOrNull()?.price
        val change = dayAgo?.let { ((market.price - it) / it) * 100.0 }
        val changeText = change?.let { "%+.2f%% (24h)".format(it) } ?: "n/a (24h)"

        val embed = EmbedBuilder()
            .setTitle("TOBY • $guildName")
            .addField("Price", "%.2f credits / coin".format(market.price), true)
            .addField("24h change", changeText, true)
            .addField("Last tick", "<t:${market.lastTickAt.epochSecond}:R>", true)
            .setColor(priceColor(change))
            .setFooter("Try /tobycoin chart to see the market")
            .build()
        event.hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun handleBalance(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        market: TobyCoinMarketDto,
        deleteDelay: Int
    ) {
        val coins = userDto.tobyCoins
        val credits = userDto.socialCredit ?: 0L
        val portfolio = (coins.toDouble() * market.price).toLong()
        val embed = EmbedBuilder()
            .setTitle("Your Toby Coin wallet")
            .addField("Coins", "$coins TOBY", true)
            .addField("Value at market", "$portfolio credits", true)
            .addField("Social credit", "$credits credits", true)
            .setFooter("Market price: %.2f credits / coin".format(market.price))
            .build()
        event.hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun handleBuy(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        deleteDelay: Int
    ) {
        val amount = event.getOption(OPT_AMOUNT)?.asLong ?: run {
            reply(event, "You must specify an amount.", deleteDelay); return
        }
        val outcome = tradeService.buy(userDto.discordId, userDto.guildId, amount)
        reply(event, describe(outcome, "Bought", amount), deleteDelay)
    }

    private fun handleSell(
        event: SlashCommandInteractionEvent,
        userDto: UserDto,
        deleteDelay: Int
    ) {
        val amount = event.getOption(OPT_AMOUNT)?.asLong ?: run {
            reply(event, "You must specify an amount.", deleteDelay); return
        }
        val outcome = tradeService.sell(userDto.discordId, userDto.guildId, amount)
        reply(event, describe(outcome, "Sold", amount), deleteDelay)
    }

    private fun handleChart(
        event: SlashCommandInteractionEvent,
        guildName: String,
        market: TobyCoinMarketDto,
        deleteDelay: Int
    ) {
        val window = event.getOption(OPT_WINDOW)?.asString ?: WINDOW_1D
        val now = Instant.now()
        val points = when (window) {
            WINDOW_ALL -> marketService.listAllHistory(market.guildId)
            WINDOW_5D -> marketService.listHistory(market.guildId, now.minus(Duration.ofDays(5)))
            WINDOW_1MO -> marketService.listHistory(market.guildId, now.minus(Duration.ofDays(30)))
            WINDOW_3MO -> marketService.listHistory(market.guildId, now.minus(Duration.ofDays(90)))
            WINDOW_1Y -> marketService.listHistory(market.guildId, now.minus(Duration.ofDays(365)))
            else -> marketService.listHistory(market.guildId, now.minus(Duration.ofDays(1)))
        }
        if (points.size < 2) {
            reply(
                event,
                "Not enough price history for a chart yet — come back after a tick or two.",
                deleteDelay
            ); return
        }

        val png = chartRenderer.renderPng(guildName, points)
        val windowLabel = when (window) {
            WINDOW_5D -> "5 days"
            WINDOW_1MO -> "1 month"
            WINDOW_3MO -> "3 months"
            WINDOW_1Y -> "1 year"
            WINDOW_ALL -> "all time"
            else -> "1 day"
        }
        val embed = EmbedBuilder()
            .setTitle("TOBY market chart — $windowLabel")
            .setDescription("Current price: **%.2f** credits / coin".format(market.price))
            .setImage("attachment://tobycoin-chart.png")
            .setColor(Color(0x57, 0xF2, 0x87))
            .build()
        event.hook.sendMessageEmbeds(embed)
            .addFiles(FileUpload.fromData(png, "tobycoin-chart.png"))
            .queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    private fun describe(outcome: TradeOutcome, verb: String, amount: Long): String = when (outcome) {
        is TradeOutcome.Ok -> ("$verb **${outcome.amount} TOBY** for ${outcome.transactedCredits} credits. " +
                "New price: %.2f. You now hold ${outcome.newCoins} TOBY and ${outcome.newCredits} credits.")
            .format(outcome.newPrice)
        is TradeOutcome.InsufficientCredits ->
            "You need ${outcome.needed} credits for this trade but only have ${outcome.have}."
        is TradeOutcome.InsufficientCoins ->
            "You need ${outcome.needed} TOBY for this trade but only have ${outcome.have}."
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
        event.hook.sendMessage(message).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }
}
