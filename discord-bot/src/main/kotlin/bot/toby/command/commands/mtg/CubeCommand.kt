package bot.toby.command.commands.mtg

import bot.toby.helpers.booleanOption
import bot.toby.helpers.intOption
import bot.toby.helpers.stringOption
import common.mtg.AsFan
import common.mtg.CardListParser
import common.mtg.CubeAnalytics
import common.mtg.CubeCard
import common.mtg.MtgCurrency
import common.mtg.MtgNames
import common.mtg.PackGenerator
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.guild.ConfigDto
import database.dto.user.UserDto
import database.service.guild.ConfigService
import database.service.user.CubeListService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.utils.FileUpload
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * `/cube` — the cube-design tool from the doc, as a Discord command.
 *
 *  - `/cube asfan`    — the as-fan calculator: (type ÷ cube) × pack size.
 *  - `/cube preview`  — fetch a pool from Scryfall and show its as-fan
 *                       distribution across colours / colourless / lands.
 *  - `/cube generate` — fetch a pool, randomly draw enough cards, and deal
 *                       them into evenly-sized, as-fan-balanced packs;
 *                       the full pack lists come back as a text file.
 *  - `/cube saved`    — list the cubes the user saved on the website, each
 *                       with its card count, ready to feed back into the
 *                       `saved` option of preview/generate.
 *
 * A "cube" is defined by a Scryfall search query (`set:vow`, `cube:vintage`,
 * `t:dragon`, …) — or, via the `saved` option, by one of the user's own
 * cubes saved on the website (resolved name-by-name through Scryfall).
 */
@Component
class CubeCommand @Autowired constructor(
    private val fetcher: ScryfallCubeFetcher,
    private val cubeListService: CubeListService,
    private val configService: ConfigService,
    // Mirrors the /dnd command: IO-bound subcommands run on this dispatcher
    // (tests pass Dispatchers.Unconfined so the launched work resolves
    // synchronously).
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MtgCommand {

    override val name: String = "cube"
    override val description: String = "Magic: The Gathering cube tools — as-fan maths and randomised draft packs."

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        when (ctx.event.subcommandName) {
            SUB_ASFAN -> handleAsFan(ctx, deleteDelay) // pure maths, no IO
            SUB_PREVIEW -> launchHandling(ctx) { handlePreview(ctx, requestingUserDto, deleteDelay) }
            SUB_GENERATE -> launchHandling(ctx) { handleGenerate(ctx, requestingUserDto) }
            SUB_SAVED -> launchHandling(ctx) { handleSaved(ctx, requestingUserDto, deleteDelay) }
            SUB_CARD -> launchHandling(ctx) { handleCard(ctx, deleteDelay) }
            SUB_RULINGS -> launchHandling(ctx) { handleRulings(ctx, deleteDelay) }
            else -> reply(ctx, CubeEmbeds.errorEmbed("Pick a subcommand: asfan, preview, generate, saved, card or rulings."), deleteDelay)
        }
    }

    /**
     * Runs an IO-bound subcommand on [dispatcher] (the Scryfall fetch and the
     * saved-cube DB read both block), mirroring the `/dnd` query handler, so
     * the work never ties up the gateway or the CPU-bound default dispatcher.
     * An unexpected failure resolves the deferred reply with an error embed
     * rather than leaving Discord's "thinking…" spinner hanging.
     */
    private fun launchHandling(ctx: CommandContext, block: suspend () -> Unit) {
        CoroutineScope(dispatcher).launch {
            runCatching { block() }.onFailure { e ->
                logger.error("Cube subcommand '${ctx.event.subcommandName}' failed: $e")
                ctx.event.hook.sendMessageEmbeds(
                    CubeEmbeds.errorEmbed("Something went wrong building that cube. Try again in a moment.")
                ).queue({}, {})
            }
        }
    }

    /** A draftable pool plus a human label, any names that didn't resolve, and an optional note. */
    private sealed interface PoolResult {
        data class Ready(
            val pool: List<CubeCard>,
            val label: String,
            val notFound: List<String> = emptyList(),
            val note: String? = null,
        ) : PoolResult
        data class Failed(val message: String) : PoolResult
    }

    /**
     * Resolves the card pool from whichever source the user gave: a saved
     * cube (looked up for their Discord account, then resolved name-by-name
     * via Scryfall) takes precedence over a Scryfall `query`.
     */
    private suspend fun resolvePool(ctx: CommandContext, requestingUserDto: UserDto): PoolResult {
        val saved = ctx.event.stringOption(OPT_SAVED)?.trim()
        if (!saved.isNullOrEmpty()) {
            val dto = cubeListService.get(requestingUserDto.discordId, saved)
                ?: return PoolResult.Failed("You have no saved cube named `$saved`. Save one on the website first.")
            val entries = CardListParser.parse(dto.cards)
            if (entries.isEmpty()) return PoolResult.Failed("Your saved cube `$saved` is empty.")
            // Resolve by front face — Scryfall's collection lookup matches a
            // single face, not the full "A // B" name; matchKeys ties the
            // returned full-name cards back to the entries below.
            return when (val res = fetcher.fetchByNames(entries.map { MtgNames.requestName(it.name) })) {
                is ScryfallCubeFetcher.Result.Failure -> PoolResult.Failed(res.message)
                is ScryfallCubeFetcher.Result.Success -> {
                    // Index by full name AND each face so a pasted front-face
                    // name (e.g. "Archangel Avacyn") matches a transform card's
                    // full name ("Archangel Avacyn // Avacyn, the Purifier").
                    val byName = HashMap<String, CubeCard>()
                    res.cards.forEach { card ->
                        MtgNames.matchKeys(card.name).forEach { key -> byName.putIfAbsent(key, card) }
                    }
                    val pool = entries.flatMap { entry ->
                        byName[MtgNames.lookupKey(entry.name)]?.let { card -> List(entry.count) { card } } ?: emptyList()
                    }
                    val notFound = entries.map { it.name }
                        .filter { byName[MtgNames.lookupKey(it)] == null }
                        .distinct()
                    if (pool.isEmpty()) PoolResult.Failed("None of `$saved`'s cards matched Scryfall.")
                    else PoolResult.Ready(pool, "saved cube \"$saved\"", notFound, capNote(res.capped))
                }
            }
        }

        val query = ctx.event.stringOption(OPT_QUERY)?.trim()
        if (query.isNullOrEmpty()) {
            return PoolResult.Failed("Give me a Scryfall `query`, or the `saved` name of one of your cubes.")
        }
        return when (val res = fetcher.fetch(query)) {
            is ScryfallCubeFetcher.Result.Failure -> PoolResult.Failed(res.message)
            is ScryfallCubeFetcher.Result.Success -> PoolResult.Ready(res.cards, query, note = capNote(res.capped))
        }
    }

    /**
     * The currency this guild's "cube value" is reported in — its
     * [ConfigDto.Configurations.CUBE_CURRENCY] config, or [MtgCurrency.DEFAULT]
     * (USD) when unset or unrecognised.
     */
    private fun currencyFor(ctx: CommandContext): MtgCurrency =
        MtgCurrency.fromCode(
            configService.getConfigByName(ConfigDto.Configurations.CUBE_CURRENCY.configValue, ctx.guild.id)?.value
        ) ?: MtgCurrency.DEFAULT

    /** A user-facing note when the pool was truncated to the Scryfall fetch ceiling. */
    private fun capNote(capped: Boolean): String? =
        if (capped) "Matched more than ${ScryfallCubeFetcher.DEFAULT_MAX_CARDS} cards; only the first ${ScryfallCubeFetcher.DEFAULT_MAX_CARDS} were used."
        else null

    private fun handleAsFan(ctx: CommandContext, deleteDelay: Int) {
        val total = ctx.event.intOption(OPT_TOTAL, 0)
        val cubeSize = ctx.event.intOption(OPT_CUBE_SIZE, 0)
        val packSize = ctx.event.intOption(OPT_PACK_SIZE, DEFAULT_PACK_SIZE)
        val embed = try {
            val value = AsFan.value(total, cubeSize, packSize)
            CubeEmbeds.asFanEmbed(total, cubeSize, packSize, value)
        } catch (e: IllegalArgumentException) {
            CubeEmbeds.errorEmbed(e.message ?: "Invalid as-fan inputs.")
        }
        reply(ctx, embed, deleteDelay)
    }

    private suspend fun handlePreview(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val packSize = ctx.event.intOption(OPT_PACK_SIZE, DEFAULT_PACK_SIZE)
        when (val resolved = resolvePool(ctx, requestingUserDto)) {
            is PoolResult.Failed -> reply(ctx, CubeEmbeds.errorEmbed(resolved.message), deleteDelay)
            is PoolResult.Ready -> {
                val pool = resolved.pool
                val currency = currencyFor(ctx)
                val embed = CubeEmbeds.previewEmbed(
                    query = resolved.label,
                    poolSize = pool.size,
                    packSize = packSize,
                    counts = AsFan.categoryCounts(pool),
                    distribution = AsFan.distribution(pool, packSize),
                    analytics = CubeAnalytics.analyze(pool, packSize),
                    notFound = resolved.notFound,
                    note = resolved.note,
                    currency = currency,
                    valueExtremes = CubeAnalytics.valueExtremes(pool, currency),
                )
                reply(ctx, embed, deleteDelay)
            }
        }
    }

    private suspend fun handleGenerate(ctx: CommandContext, requestingUserDto: UserDto) {
        val packCount = ctx.event.intOption(OPT_PACKS, DEFAULT_PACK_COUNT)
        val packSize = ctx.event.intOption(OPT_PACK_SIZE, DEFAULT_PACK_SIZE)
        val balanced = ctx.event.booleanOption(OPT_BALANCED, true)

        when (val resolved = resolvePool(ctx, requestingUserDto)) {
            is PoolResult.Failed -> ctx.event.hook.sendMessageEmbeds(CubeEmbeds.errorEmbed(resolved.message)).queue()
            is PoolResult.Ready -> {
                val pool = resolved.pool
                when (val packs = PackGenerator(Random.Default).generate(pool, packCount, packSize, balanced)) {
                    is PackGenerator.Result.Failure -> ctx.event.hook.sendMessageEmbeds(CubeEmbeds.errorEmbed(packs.reason)).queue()
                    is PackGenerator.Result.Success -> {
                        val selected = packs.value.cards
                        val currency = currencyFor(ctx)
                        val embed = CubeEmbeds.generateEmbed(
                            query = resolved.label,
                            poolSize = pool.size,
                            packCount = packCount,
                            packSize = packSize,
                            balanced = balanced,
                            selected = selected,
                            counts = AsFan.categoryCounts(selected),
                            distribution = AsFan.distribution(selected, packSize),
                            notFound = resolved.notFound,
                            note = resolved.note,
                            currency = currency,
                        )
                        val file = FileUpload.fromData(CubeEmbeds.packsFile(packs.value.packs, currency), ATTACHMENT_NAME)
                        ctx.event.hook.sendMessageEmbeds(embed).addFiles(file).queue()
                    }
                }
            }
        }
    }

    /** Lists the cubes this user has saved on the website. */
    private suspend fun handleSaved(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        val saved = cubeListService.listForUser(requestingUserDto.discordId)
        reply(ctx, CubeEmbeds.savedCubesEmbed(saved), deleteDelay)
    }

    /** Looks up a single card by name on Scryfall and shows its image + facts. */
    private suspend fun handleCard(ctx: CommandContext, deleteDelay: Int) {
        val name = ctx.event.stringOption(OPT_NAME)?.trim()
        if (name.isNullOrEmpty()) {
            reply(ctx, CubeEmbeds.errorEmbed("Give me a card `name` to look up."), deleteDelay)
            return
        }
        when (val res = fetcher.fetchByNames(listOf(MtgNames.requestName(name)))) {
            is ScryfallCubeFetcher.Result.Failure ->
                reply(ctx, CubeEmbeds.errorEmbed("Couldn't find a card named `$name`."), deleteDelay)
            is ScryfallCubeFetcher.Result.Success -> {
                // Prefer the card whose full/face name matches what was typed.
                val card = res.cards.firstOrNull { MtgNames.matchKeys(it.name).contains(MtgNames.lookupKey(name)) }
                    ?: res.cards.firstOrNull()
                if (card == null) reply(ctx, CubeEmbeds.errorEmbed("Couldn't find a card named `$name`."), deleteDelay)
                else reply(ctx, CubeEmbeds.cardEmbed(card), deleteDelay)
            }
        }
    }

    /** Looks up a single card's official rulings on Scryfall. */
    private suspend fun handleRulings(ctx: CommandContext, deleteDelay: Int) {
        val name = ctx.event.stringOption(OPT_NAME)?.trim()
        if (name.isNullOrEmpty()) {
            reply(ctx, CubeEmbeds.errorEmbed("Give me a card `name` to look up rulings for."), deleteDelay)
            return
        }
        when (val rulings = fetcher.fetchRulings(name)) {
            null -> reply(ctx, CubeEmbeds.errorEmbed("Couldn't find a card named `$name`."), deleteDelay)
            else -> reply(ctx, CubeEmbeds.rulingsEmbed(rulings), deleteDelay)
        }
    }

    private fun reply(ctx: CommandContext, embed: MessageEmbed, deleteDelay: Int) {
        ctx.event.hook.sendMessageEmbeds(embed).queue(invokeDeleteOnMessageResponse(deleteDelay))
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_ASFAN, "Expected copies of a card type per booster: (type ÷ cube) × pack size.")
            .addOptions(
                OptionData(OptionType.INTEGER, OPT_TOTAL, "How many cards of that type are in the cube.", true)
                    .setMinValue(0),
                OptionData(OptionType.INTEGER, OPT_CUBE_SIZE, "Total cards in the cube.", true)
                    .setMinValue(1),
                OptionData(OptionType.INTEGER, OPT_PACK_SIZE, "Cards per pack (default 15).", false)
                    .setMinValue(1).setMaxValue(MAX_PACK_SIZE.toLong()),
            ),
        SubcommandData(SUB_PREVIEW, "Show the as-fan distribution of a cube (a Scryfall query or a saved cube).")
            .addOptions(
                OptionData(OptionType.STRING, OPT_QUERY, "Scryfall search defining the cube (e.g. set:vow).", false),
                OptionData(OptionType.STRING, OPT_SAVED, "Name of a cube you saved on the website (instead of a query).", false)
                    .setAutoComplete(true),
                OptionData(OptionType.INTEGER, OPT_PACK_SIZE, "Cards per pack (default 15).", false)
                    .setMinValue(1).setMaxValue(MAX_PACK_SIZE.toLong()),
            ),
        SubcommandData(SUB_GENERATE, "Deal randomised, as-fan-balanced packs from a Scryfall query or a saved cube.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_QUERY, "Scryfall search defining the pool (e.g. cube:vintage).", false),
                OptionData(OptionType.STRING, OPT_SAVED, "Name of a cube you saved on the website (instead of a query).", false)
                    .setAutoComplete(true),
                OptionData(OptionType.INTEGER, OPT_PACKS, "How many packs to build (default 24).", false)
                    .setMinValue(1).setMaxValue(MAX_PACK_COUNT.toLong()),
                OptionData(OptionType.INTEGER, OPT_PACK_SIZE, "Cards per pack (default 15).", false)
                    .setMinValue(1).setMaxValue(MAX_PACK_SIZE.toLong()),
                OptionData(OptionType.BOOLEAN, OPT_BALANCED, "Level colours/lands across packs (default true).", false),
            ),
        SubcommandData(SUB_SAVED, "List the cubes you've saved on the website."),
        SubcommandData(SUB_CARD, "Look up a single Magic card by name.")
            .addOptions(OptionData(OptionType.STRING, OPT_NAME, "The card's name (e.g. Lightning Bolt).", true)),
        SubcommandData(SUB_RULINGS, "Look up a Magic card's official rulings by name.")
            .addOptions(OptionData(OptionType.STRING, OPT_NAME, "The card's name (e.g. Doubling Season).", true)),
    )

    companion object {
        const val SUB_ASFAN = "asfan"
        const val SUB_PREVIEW = "preview"
        const val SUB_GENERATE = "generate"
        const val SUB_SAVED = "saved"
        const val SUB_CARD = "card"
        const val SUB_RULINGS = "rulings"

        const val OPT_TOTAL = "total"
        const val OPT_NAME = "name"
        const val OPT_CUBE_SIZE = "cube-size"
        const val OPT_PACK_SIZE = "pack-size"
        const val OPT_QUERY = "query"
        const val OPT_SAVED = "saved"
        const val OPT_PACKS = "packs"
        const val OPT_BALANCED = "balanced"

        const val DEFAULT_PACK_SIZE = 15
        const val DEFAULT_PACK_COUNT = 24
        const val MAX_PACK_SIZE = 30
        const val MAX_PACK_COUNT = 90

        private const val ATTACHMENT_NAME = "cube-packs.txt"
    }
}
