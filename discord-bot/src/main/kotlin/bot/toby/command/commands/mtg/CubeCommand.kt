package bot.toby.command.commands.mtg

import bot.toby.helpers.booleanOption
import bot.toby.helpers.intOption
import bot.toby.helpers.stringOption
import common.mtg.AsFan
import common.mtg.CubeAnalytics
import common.mtg.MtgCommandRef
import common.mtg.MtgCurrency
import common.mtg.PackGenerator
import core.command.CommandContext
import database.dto.guild.ConfigDto
import database.dto.user.UserDto
import database.service.guild.ConfigService
import database.service.user.CubeListService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.utils.FileUpload
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import kotlin.random.Random

/**
 * `/mtgcube` — the cube-design tools: as-fan maths, pack preview/generation, and
 * listing your saved cubes. Card lookups, deck legality, set/keyword reference
 * and price watches each live in their own command ([CardCommand],
 * [DeckCommand], [MtgReferenceCommand], [PriceWatchCommand]).
 *
 * A "cube" is defined by a Scryfall search query (`set:vow`, `cube:vintage`,
 * `t:dragon`, …) — or, via the `saved` option, by one of the user's own cubes
 * saved on the website (resolved name-by-name through Scryfall).
 */
@Component
class CubeCommand @Autowired constructor(
    private val resolver: MtgPoolResolver,
    private val cubeListService: CubeListService,
    private val configService: ConfigService,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AbstractMtgCommand(dispatcher) {

    override val name: String = MtgCommandRef.CUBE
    override val description: String = "Magic: The Gathering cube tools — as-fan maths and randomised draft packs."

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        when (ctx.event.subcommandName) {
            SUB_ASFAN -> handleAsFan(ctx, deleteDelay) // pure maths, no IO
            SUB_PREVIEW -> launchHandling(ctx) { handlePreview(ctx, requestingUserDto, deleteDelay) }
            SUB_GENERATE -> launchHandling(ctx) { handleGenerate(ctx, requestingUserDto) }
            SUB_SAVED -> launchHandling(ctx) { handleSaved(ctx, requestingUserDto, deleteDelay) }
            else -> reply(ctx, CubeEmbeds.errorEmbed("Pick a subcommand: asfan, preview, generate or saved."), deleteDelay)
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

    private suspend fun resolvePool(ctx: CommandContext, requestingUserDto: UserDto) =
        resolver.resolve(ctx.event.stringOption(OPT_SAVED), ctx.event.stringOption(OPT_QUERY), requestingUserDto.discordId)

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
            is MtgPoolResolver.PoolResult.Failed -> reply(ctx, CubeEmbeds.errorEmbed(resolved.message), deleteDelay)
            is MtgPoolResolver.PoolResult.Ready -> {
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
            is MtgPoolResolver.PoolResult.Failed -> ctx.event.hook.sendMessageEmbeds(CubeEmbeds.errorEmbed(resolved.message)).queue()
            is MtgPoolResolver.PoolResult.Ready -> {
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
    )

    companion object {
        const val SUB_ASFAN = MtgCommandRef.Cube.ASFAN
        const val SUB_PREVIEW = MtgCommandRef.Cube.PREVIEW
        const val SUB_GENERATE = MtgCommandRef.Cube.GENERATE
        const val SUB_SAVED = MtgCommandRef.Cube.SAVED

        const val OPT_TOTAL = "total"
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
