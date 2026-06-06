package bot.toby.command.commands.mtg

import bot.toby.helpers.stringOption
import common.mtg.MtgNames
import core.command.CommandContext
import database.dto.user.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * `/card` — single-card lookups: image + facts (`lookup`), official rulings
 * (`rulings`), and the combos a card appears in (`combos`, via Commander
 * Spellbook). Card-centric, not cube-specific.
 */
@Component
class CardCommand @Autowired constructor(
    private val fetcher: ScryfallCubeFetcher,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AbstractMtgCommand(dispatcher) {

    override val name: String = "card"
    override val description: String = "Look up a Magic card — its details, official rulings, or combos."

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        when (ctx.event.subcommandName) {
            SUB_LOOKUP -> launchHandling(ctx) { handleLookup(ctx, deleteDelay) }
            SUB_RULINGS -> launchHandling(ctx) { handleRulings(ctx, deleteDelay) }
            SUB_COMBOS -> launchHandling(ctx) { handleCombos(ctx, deleteDelay) }
            else -> reply(ctx, CubeEmbeds.errorEmbed("Pick a subcommand: lookup, rulings or combos."), deleteDelay)
        }
    }

    /** Looks up a single card by name on Scryfall and shows its image + facts. */
    private suspend fun handleLookup(ctx: CommandContext, deleteDelay: Int) {
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

    /** Looks up the combos a single card appears in, via Commander Spellbook. */
    private suspend fun handleCombos(ctx: CommandContext, deleteDelay: Int) {
        val name = ctx.event.stringOption(OPT_NAME)?.trim()
        if (name.isNullOrEmpty()) {
            reply(ctx, CubeEmbeds.errorEmbed("Give me a card `name` to find combos for."), deleteDelay)
            return
        }
        when (val combos = fetcher.fetchCombos(name)) {
            null -> reply(ctx, CubeEmbeds.errorEmbed("Couldn't reach Commander Spellbook. Try again later."), deleteDelay)
            else -> reply(ctx, CubeEmbeds.combosEmbed(combos), deleteDelay)
        }
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_LOOKUP, "Look up a single Magic card by name.")
            .addOptions(OptionData(OptionType.STRING, OPT_NAME, "The card's name (e.g. Lightning Bolt).", true)),
        SubcommandData(SUB_RULINGS, "Look up a Magic card's official rulings by name.")
            .addOptions(OptionData(OptionType.STRING, OPT_NAME, "The card's name (e.g. Doubling Season).", true)),
        SubcommandData(SUB_COMBOS, "Find the combos a Magic card is part of (Commander Spellbook).")
            .addOptions(OptionData(OptionType.STRING, OPT_NAME, "The card's name (e.g. Thassa's Oracle).", true)),
    )

    companion object {
        const val SUB_LOOKUP = "lookup"
        const val SUB_RULINGS = "rulings"
        const val SUB_COMBOS = "combos"

        const val OPT_NAME = "name"
    }
}
