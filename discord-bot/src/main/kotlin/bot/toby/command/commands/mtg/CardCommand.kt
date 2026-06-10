package bot.toby.command.commands.mtg

import bot.toby.helpers.stringOption
import common.mtg.CubeCard
import common.mtg.MtgCommandRef
import common.mtg.MtgNames
import core.command.CommandContext
import database.dto.user.UserDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.Base64

/**
 * `/mtgcard` — card lookups and search: a multi-card Scryfall search browser
 * (`search`), single-card image + facts (`lookup`), official rulings
 * (`rulings`), and the combos a card appears in (`combos`, via Commander
 * Spellbook). Card-centric, not cube-specific.
 */
@Component
class CardCommand @Autowired constructor(
    private val fetcher: ScryfallCubeFetcher,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AbstractMtgCommand(dispatcher) {

    override val name: String = MtgCommandRef.CARD
    override val description: String = "Search Magic cards, or look up a card's details, rulings, or combos."

    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int) {
        logger.setGuildAndMemberContext(ctx.guild, ctx.member)
        when (ctx.event.subcommandName) {
            SUB_SEARCH -> launchHandling(ctx) { handleSearch(ctx, deleteDelay) }
            SUB_LOOKUP -> launchHandling(ctx) { handleLookup(ctx, deleteDelay) }
            SUB_RULINGS -> launchHandling(ctx) { handleRulings(ctx, deleteDelay) }
            SUB_COMBOS -> launchHandling(ctx) { handleCombos(ctx, deleteDelay) }
            else -> replyError(ctx, "Pick a subcommand: search, lookup, rulings or combos.", deleteDelay)
        }
    }

    /**
     * Searches Scryfall for every card matching a name fragment, a card type,
     * and/or a raw Scryfall query, then sends a one-card-at-a-time browser
     * (Prev / Next) — the Discord-native take on Scryfall's image grid of
     * results.
     */
    private suspend fun handleSearch(ctx: CommandContext, deleteDelay: Int) {
        val query = buildSearchQuery(
            name = ctx.event.stringOption(OPT_NAME)?.trim(),
            type = ctx.event.stringOption(OPT_TYPE)?.trim(),
            raw = ctx.event.stringOption(OPT_QUERY)?.trim(),
        )
        if (query.isBlank()) {
            replyError(ctx, "Give me something to search: a `name`, a `type`, or a Scryfall `query`.", deleteDelay)
            return
        }
        when (val res = fetcher.fetch(query, SEARCH_MAX)) {
            is ScryfallCubeFetcher.Result.Failure -> replyError(ctx, res.message, deleteDelay)
            is ScryfallCubeFetcher.Result.Success -> sendSearchPage(ctx, query, res.cards, res.capped, 0, deleteDelay)
        }
    }

    /**
     * Sends the page at [index] of a search's [cards]. A lone result is a
     * plain self-deleting card panel; a multi-card result becomes a persistent
     * browser whose Prev/Next buttons re-run the [query] (stateless, like the
     * excuse paginator) and re-render the chosen card.
     */
    private fun sendSearchPage(
        ctx: CommandContext,
        query: String,
        cards: List<CubeCard>,
        capped: Boolean,
        index: Int,
        deleteDelay: Int,
    ) {
        if (cards.size == 1) {
            reply(ctx, CubeEmbeds.cardEmbed(cards.first()), deleteDelay)
            return
        }
        val i = index.coerceIn(0, cards.lastIndex)
        val embed = CubeEmbeds.searchResultEmbed(cards[i], i, cards.size, capped)
        val content = "🔎 Found **${cards.size}${if (capped) "+" else ""}** cards matching `$query`."
        val row = searchRow(query, i, cards.size)
        if (row == null) {
            reply(ctx, embed, deleteDelay)
            return
        }
        ctx.event.hook.sendMessage(content).addEmbeds(embed).addComponents(row).queue()
    }

    /** Looks up a single card by name on Scryfall and shows its image + facts. */
    private suspend fun handleLookup(ctx: CommandContext, deleteDelay: Int) {
        val name = ctx.event.stringOption(OPT_NAME)?.trim()
        if (name.isNullOrEmpty()) {
            replyError(ctx, "Give me a card `name` to look up.", deleteDelay)
            return
        }
        when (val res = fetcher.fetchByNames(listOf(MtgNames.requestName(name)))) {
            is ScryfallCubeFetcher.Result.Failure ->
                replyError(ctx, "Couldn't find a card named `$name`.", deleteDelay)
            is ScryfallCubeFetcher.Result.Success -> {
                // Prefer the card whose full/face name matches what was typed.
                val card = res.cards.firstOrNull { MtgNames.matchKeys(it.name).contains(MtgNames.lookupKey(name)) }
                    ?: res.cards.firstOrNull()
                if (card == null) replyError(ctx, "Couldn't find a card named `$name`.", deleteDelay)
                else reply(ctx, CubeEmbeds.cardEmbed(card), deleteDelay)
            }
        }
    }

    /** Looks up a single card's official rulings on Scryfall. */
    private suspend fun handleRulings(ctx: CommandContext, deleteDelay: Int) {
        val name = ctx.event.stringOption(OPT_NAME)?.trim()
        if (name.isNullOrEmpty()) {
            replyError(ctx, "Give me a card `name` to look up rulings for.", deleteDelay)
            return
        }
        when (val rulings = fetcher.fetchRulings(name)) {
            null -> replyError(ctx, "Couldn't find a card named `$name`.", deleteDelay)
            else -> reply(ctx, CubeEmbeds.rulingsEmbed(rulings), deleteDelay)
        }
    }

    /** Looks up the combos a single card appears in, via Commander Spellbook. */
    private suspend fun handleCombos(ctx: CommandContext, deleteDelay: Int) {
        val name = ctx.event.stringOption(OPT_NAME)?.trim()
        if (name.isNullOrEmpty()) {
            replyError(ctx, "Give me a card `name` to find combos for.", deleteDelay)
            return
        }
        when (val combos = fetcher.fetchCombos(name)) {
            null -> replyError(ctx, "Couldn't reach Commander Spellbook. Try again later.", deleteDelay)
            else -> reply(ctx, CubeEmbeds.combosEmbed(combos), deleteDelay)
        }
    }

    override val subCommands: List<SubcommandData> = listOf(
        SubcommandData(SUB_SEARCH, "Search Magic cards by name, type and/or Scryfall query — browse the matches.")
            .addOptions(
                OptionData(OptionType.STRING, OPT_NAME, "Cards whose name includes this (e.g. iron man).", false),
                OptionData(OptionType.STRING, OPT_TYPE, "Card type(s) to require (e.g. creature, legendary artifact).", false),
                OptionData(OptionType.STRING, OPT_QUERY, "Raw Scryfall query, ANDed with the above (e.g. c:r mv<=2).", false),
            ),
        SubcommandData(SUB_LOOKUP, "Look up a single Magic card by name.")
            .addOptions(OptionData(OptionType.STRING, OPT_NAME, "The card's name (e.g. Lightning Bolt).", true)),
        SubcommandData(SUB_RULINGS, "Look up a Magic card's official rulings by name.")
            .addOptions(OptionData(OptionType.STRING, OPT_NAME, "The card's name (e.g. Doubling Season).", true)),
        SubcommandData(SUB_COMBOS, "Find the combos a Magic card is part of (Commander Spellbook).")
            .addOptions(OptionData(OptionType.STRING, OPT_NAME, "The card's name (e.g. Thassa's Oracle).", true)),
    )

    companion object {
        const val SUB_SEARCH = MtgCommandRef.Card.SEARCH
        const val SUB_LOOKUP = MtgCommandRef.Card.LOOKUP
        const val SUB_RULINGS = MtgCommandRef.Card.RULINGS
        const val SUB_COMBOS = MtgCommandRef.Card.COMBOS

        const val OPT_NAME = "name"
        const val OPT_TYPE = "type"
        const val OPT_QUERY = "query"

        /**
         * Cards fetched per search: one Scryfall page, so a click re-runs a
         * single HTTP request. The footer flags `+` when more exist than this.
         */
        const val SEARCH_MAX = 175

        /** Button-id prefix the dispatcher routes search pagination clicks on. */
        const val SEARCH_BUTTON = "mtgcard-search"

        /**
         * Builds the Scryfall query from the structured search options. Shared
         * with the web card search via [common.mtg.MtgSearchQuery] so the two
         * surfaces read a name + type identically.
         */
        fun buildSearchQuery(name: String?, type: String?, raw: String?): String =
            common.mtg.MtgSearchQuery.build(name, type, raw)

        /**
         * The Prev / page-indicator / Next action row for a search browser, or
         * null when pagination can't be offered — a single page, or a query so
         * long its encoded button id would blow Discord's 100-char component-id
         * cap (in which case the caller shows a static first page).
         */
        fun searchRow(query: String, index: Int, total: Int): ActionRow? {
            if (total <= 1) return null
            val prevId = encodeSearchButton(query, index - 1)
            val nextId = encodeSearchButton(query, index + 1)
            if (prevId.length > COMPONENT_ID_LIMIT || nextId.length > COMPONENT_ID_LIMIT) return null
            return ActionRow.of(
                Button.primary(prevId, "⬅️ Prev").withDisabled(index <= 0),
                Button.secondary("$SEARCH_BUTTON:noop", "${index + 1} / $total").withDisabled(true),
                Button.primary(nextId, "Next ➡️").withDisabled(index >= total - 1),
            )
        }

        private const val COMPONENT_ID_LIMIT = 100

        /**
         * Encodes a search-pagination button id as `mtgcard-search:<index>:<b64>`,
         * the query base64'd (url-safe, no padding) so its spaces, quotes and
         * `:`s survive Discord's component id. Decode with [decodeSearchButton].
         */
        fun encodeSearchButton(query: String, index: Int): String {
            val q = Base64.getUrlEncoder().withoutPadding().encodeToString(query.toByteArray(Charsets.UTF_8))
            return "$SEARCH_BUTTON:$index:$q"
        }

        /** Parses an [encodeSearchButton] id, or null when it isn't one we recognise. */
        fun decodeSearchButton(componentId: String): DecodedSearchButton? {
            val parts = componentId.split(":", limit = 3)
            if (parts.size < 3 || parts[0] != SEARCH_BUTTON) return null
            val index = parts[1].toIntOrNull() ?: return null
            val query = runCatching {
                String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
            return DecodedSearchButton(query, index)
        }
    }

    data class DecodedSearchButton(val query: String, val index: Int)
}
