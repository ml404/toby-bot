package bot.toby.command.commands.mtg

import common.mtg.CardListParser
import common.mtg.CubeCard
import common.mtg.MtgNames
import database.service.user.CubeListService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Resolves a draftable card pool from whichever source a user gave — a saved
 * cube (looked up for their Discord account, then resolved name-by-name via
 * Scryfall) which takes precedence over a Scryfall `query`. Shared by the
 * cube tools (`/mtgcube preview` / `/mtgcube generate`) and the deck checker
 * (`/mtgdeck legality`) so they resolve pools identically.
 */
@Component
class MtgPoolResolver @Autowired constructor(
    private val fetcher: ScryfallCubeFetcher,
    private val cubeListService: CubeListService,
) {

    /** A draftable pool plus a human label, any names that didn't resolve, and an optional note. */
    sealed interface PoolResult {
        data class Ready(
            val pool: List<CubeCard>,
            val label: String,
            val notFound: List<String> = emptyList(),
            val note: String? = null,
        ) : PoolResult
        data class Failed(val message: String) : PoolResult
    }

    /** Resolve from a saved cube name (preferred) or a Scryfall query. */
    suspend fun resolve(savedName: String?, query: String?, discordId: Long): PoolResult {
        val saved = savedName?.trim()
        if (!saved.isNullOrEmpty()) {
            val dto = cubeListService.get(discordId, saved)
                ?: return PoolResult.Failed("You have no saved cube named `$saved`. Save one on the website first.")
            val entries = CardListParser.parse(dto.cards)
            if (entries.isEmpty()) return PoolResult.Failed("Your saved cube `$saved` is empty.")
            // Resolve by front face — Scryfall's collection lookup matches a
            // single face, not the full "A // B" name; matchKeys ties the
            // returned full-name cards back to the entries below.
            return when (val res = fetcher.fetchByNames(entries.map { MtgNames.requestName(it.name) })) {
                is ScryfallCubeFetcher.Result.Failure -> PoolResult.Failed(res.message)
                is ScryfallCubeFetcher.Result.Success -> {
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

        val q = query?.trim()
        if (q.isNullOrEmpty()) {
            return PoolResult.Failed("Give me a Scryfall `query`, or the `saved` name of one of your cubes.")
        }
        return when (val res = fetcher.fetch(q)) {
            is ScryfallCubeFetcher.Result.Failure -> PoolResult.Failed(res.message)
            is ScryfallCubeFetcher.Result.Success -> PoolResult.Ready(res.cards, q, note = capNote(res.capped))
        }
    }

    /** A user-facing note when the pool was truncated to the Scryfall fetch ceiling. */
    fun capNote(capped: Boolean): String? =
        if (capped) "Matched more than ${ScryfallCubeFetcher.DEFAULT_MAX_CARDS} cards; only the first ${ScryfallCubeFetcher.DEFAULT_MAX_CARDS} were used."
        else null
}
