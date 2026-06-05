package bot.toby.command.commands.mtg

import bot.toby.helpers.booleanOption
import bot.toby.helpers.intOption
import bot.toby.helpers.stringOption
import common.mtg.AsFan
import common.mtg.PackGenerator
import core.command.Command.Companion.invokeDeleteOnMessageResponse
import core.command.CommandContext
import database.dto.user.UserDto
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
 *
 * A "cube" is defined by a Scryfall search query, so the user picks their
 * pool with the same syntax they'd use on scryfall.com (`set:vow`,
 * `cube:vintage`, `t:dragon`, …).
 */
@Component
class CubeCommand @Autowired constructor(
    private val fetcher: ScryfallCubeFetcher,
) : MtgCommand {

    override val name: String = "cube"
    override val description: String = "Magic: The Gathering cube tools — as-fan maths and randomised draft packs."

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        when (ctx.event.subcommandName) {
            SUB_ASFAN -> handleAsFan(ctx, deleteDelay)
            SUB_PREVIEW -> handlePreview(ctx, deleteDelay)
            SUB_GENERATE -> handleGenerate(ctx)
            else -> reply(ctx, CubeEmbeds.errorEmbed("Pick a subcommand: asfan, preview or generate."), deleteDelay)
        }
    }

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

    private fun handlePreview(ctx: CommandContext, deleteDelay: Int) {
        val query = ctx.event.stringOption(OPT_QUERY).orEmpty()
        val packSize = ctx.event.intOption(OPT_PACK_SIZE, DEFAULT_PACK_SIZE)
        when (val result = fetcher.fetch(query)) {
            is ScryfallCubeFetcher.Result.Failure -> reply(ctx, CubeEmbeds.errorEmbed(result.message), deleteDelay)
            is ScryfallCubeFetcher.Result.Success -> {
                val pool = result.cards
                val embed = CubeEmbeds.previewEmbed(
                    query = query.trim(),
                    poolSize = pool.size,
                    packSize = packSize,
                    counts = AsFan.categoryCounts(pool),
                    distribution = AsFan.distribution(pool, packSize),
                )
                reply(ctx, embed, deleteDelay)
            }
        }
    }

    private fun handleGenerate(ctx: CommandContext) {
        val query = ctx.event.stringOption(OPT_QUERY).orEmpty()
        val packCount = ctx.event.intOption(OPT_PACKS, DEFAULT_PACK_COUNT)
        val packSize = ctx.event.intOption(OPT_PACK_SIZE, DEFAULT_PACK_SIZE)
        val balanced = ctx.event.booleanOption(OPT_BALANCED, true)

        when (val result = fetcher.fetch(query)) {
            is ScryfallCubeFetcher.Result.Failure -> ctx.event.hook.sendMessageEmbeds(CubeEmbeds.errorEmbed(result.message)).queue()
            is ScryfallCubeFetcher.Result.Success -> {
                val pool = result.cards
                when (val packs = PackGenerator(Random.Default).generate(pool, packCount, packSize, balanced)) {
                    is PackGenerator.Result.Failure -> ctx.event.hook.sendMessageEmbeds(CubeEmbeds.errorEmbed(packs.reason)).queue()
                    is PackGenerator.Result.Success -> {
                        val selected = packs.value.cards
                        val embed = CubeEmbeds.generateEmbed(
                            query = query.trim(),
                            poolSize = pool.size,
                            packCount = packCount,
                            packSize = packSize,
                            balanced = balanced,
                            selected = selected,
                            counts = AsFan.categoryCounts(selected),
                            distribution = AsFan.distribution(selected, packSize),
                        )
                        val file = FileUpload.fromData(CubeEmbeds.packsFile(packs.value.packs), ATTACHMENT_NAME)
                        ctx.event.hook.sendMessageEmbeds(embed).addFiles(file).queue()
                    }
                }
            }
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
        SubcommandData(SUB_PREVIEW, "Show the as-fan distribution of a Scryfall query's cards.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_QUERY, "Scryfall search defining the cube (e.g. set:vow).", true),
                OptionData(OptionType.INTEGER, OPT_PACK_SIZE, "Cards per pack (default 15).", false)
                    .setMinValue(1).setMaxValue(MAX_PACK_SIZE.toLong()),
            ),
        SubcommandData(SUB_GENERATE, "Draw a cube from Scryfall and deal randomised, as-fan-balanced packs.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_QUERY, "Scryfall search defining the pool (e.g. cube:vintage).", true),
                OptionData(OptionType.INTEGER, OPT_PACKS, "How many packs to build (default 24).", false)
                    .setMinValue(1).setMaxValue(MAX_PACK_COUNT.toLong()),
                OptionData(OptionType.INTEGER, OPT_PACK_SIZE, "Cards per pack (default 15).", false)
                    .setMinValue(1).setMaxValue(MAX_PACK_SIZE.toLong()),
                OptionData(OptionType.BOOLEAN, OPT_BALANCED, "Level colours/lands across packs (default true).", false),
            ),
    )

    companion object {
        const val SUB_ASFAN = "asfan"
        const val SUB_PREVIEW = "preview"
        const val SUB_GENERATE = "generate"

        const val OPT_TOTAL = "total"
        const val OPT_CUBE_SIZE = "cube-size"
        const val OPT_PACK_SIZE = "pack-size"
        const val OPT_QUERY = "query"
        const val OPT_PACKS = "packs"
        const val OPT_BALANCED = "balanced"

        const val DEFAULT_PACK_SIZE = 15
        const val DEFAULT_PACK_COUNT = 24
        const val MAX_PACK_SIZE = 30
        const val MAX_PACK_COUNT = 90

        private const val ATTACHMENT_NAME = "cube-packs.txt"
    }
}
