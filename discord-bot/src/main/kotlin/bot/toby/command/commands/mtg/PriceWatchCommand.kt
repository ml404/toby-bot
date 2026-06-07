package bot.toby.command.commands.mtg

import bot.toby.helpers.stringOption
import common.mtg.MtgCommandRef
import common.mtg.MtgCurrency
import core.command.CommandContext
import database.dto.user.CardPriceWatchDto
import database.dto.user.UserDto
import database.service.user.CardPriceWatchService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/mtgprice` — get DM'd when a Magic card's market price crosses a target.
 * `add` resolves the card and captures its current price; `list` and `remove`
 * manage your watches. The scheduled [bot.toby.scheduling.CardPriceWatchJob]
 * does the periodic checking and DMing.
 */
@Component
class PriceWatchCommand @Autowired constructor(
    private val fetcher: ScryfallCubeFetcher,
    private val priceWatchService: CardPriceWatchService,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AbstractMtgCommand(dispatcher) {

    override val name: String = MtgCommandRef.PRICEWATCH
    override val description: String = "Get DM'd when a Magic card's price crosses your target."

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        when (ctx.event.subcommandName) {
            SUB_ADD -> launchHandling(ctx) { handleAdd(ctx, requestingUserDto, deleteDelay) }
            SUB_LIST -> handleList(ctx, requestingUserDto, deleteDelay) // DB read, no network
            SUB_REMOVE -> handleRemove(ctx, requestingUserDto, deleteDelay)
            else -> replyError(ctx, "Pick a subcommand: add, list or remove.", deleteDelay)
        }
    }

    /** Adds a price watch: resolves the card, captures its current price, persists. */
    private suspend fun handleAdd(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val name = ctx.event.stringOption(OPT_NAME)?.trim()
        val directionKey = ctx.event.stringOption(OPT_DIRECTION)?.trim()?.uppercase()
        val price = ctx.event.getOption(OPT_PRICE)?.asDouble
        val currency = MtgCurrency.fromCode(ctx.event.stringOption(OPT_CURRENCY)) ?: MtgCurrency.DEFAULT
        if (name.isNullOrEmpty()) {
            replyError(ctx, "Give me a card `name` to watch.", deleteDelay); return
        }
        val direction = directionKey?.let { runCatching { CardPriceWatchDto.Direction.valueOf(it) }.getOrNull() }
        if (direction == null) {
            replyError(ctx, "Pick a `direction`: below or above.", deleteDelay); return
        }
        if (price == null || price <= 0.0) {
            replyError(ctx, "Give me a positive target `price`.", deleteDelay); return
        }
        val card = fetcher.fetchNamed(name)
        if (card == null) {
            replyError(ctx, "Couldn't find a card named `$name`.", deleteDelay); return
        }
        val currentPrice = card.price(currency)?.toDoubleOrNull()
        val created = priceWatchService.create(
            discordId = requestingUserDto.discordId,
            guildId = ctx.guild.idLong,
            cardName = card.name,
            currency = currency.code,
            direction = direction,
            threshold = price,
            priceAtCreation = currentPrice,
        )
        if (created == null) {
            replyError(ctx, "You've hit the watch limit (${priceWatchService.maxPerUser}). Remove one with `${MtgCommandRef.PRICEWATCH_REMOVE}` first.", deleteDelay)
        } else {
            reply(ctx, CubeEmbeds.watchAddedEmbed(created, card, currency, currentPrice), deleteDelay)
        }
    }

    /** Lists the caller's card price watches. */
    private fun handleList(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val watches = priceWatchService.listForUser(requestingUserDto.discordId)
        reply(ctx, CubeEmbeds.watchListEmbed(watches), deleteDelay)
    }

    /** Removes one of the caller's watches by id (ownership-checked). */
    private fun handleRemove(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val id = ctx.event.getOption(OPT_WATCH_ID)?.asLong
        if (id == null) {
            replyError(ctx, "Give me the watch `id` to remove (see `${MtgCommandRef.PRICEWATCH_LIST}`).", deleteDelay); return
        }
        val removed = priceWatchService.remove(id, requestingUserDto.discordId)
        reply(
            ctx,
            if (removed) CubeEmbeds.watchRemovedEmbed(id)
            else CubeEmbeds.errorEmbed("No watch #$id of yours to remove."),
            deleteDelay,
        )
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_ADD, "Get DM'd when a card's price crosses your target.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_NAME, "The card to watch (e.g. Ragavan).", true),
                OptionData(OptionType.STRING, OPT_DIRECTION, "Alert when the price goes…", true)
                    .addChoice("below", CardPriceWatchDto.Direction.BELOW.name)
                    .addChoice("above", CardPriceWatchDto.Direction.ABOVE.name),
                OptionData(OptionType.NUMBER, OPT_PRICE, "Target price.", true).setMinValue(0.0),
                OptionData(OptionType.STRING, OPT_CURRENCY, "Currency (default USD).", false)
                    .addChoices(MtgCurrency.entries.map { net.dv8tion.jda.api.interactions.commands.Command.Choice(it.display, it.code) }),
            ),
        SubcommandData(SUB_LIST, "List your card price watches."),
        SubcommandData(SUB_REMOVE, "Remove one of your card price watches.")
            .addOptions(OptionData(OptionType.INTEGER, OPT_WATCH_ID, "The watch id (from ${MtgCommandRef.PRICEWATCH_LIST}).", true).setMinValue(1)),
    )

    companion object {
        const val SUB_ADD = MtgCommandRef.PriceWatch.ADD
        const val SUB_LIST = MtgCommandRef.PriceWatch.LIST
        const val SUB_REMOVE = MtgCommandRef.PriceWatch.REMOVE

        const val OPT_NAME = "name"
        const val OPT_DIRECTION = "direction"
        const val OPT_PRICE = "price"
        const val OPT_CURRENCY = "currency"
        const val OPT_WATCH_ID = "id"
    }
}
