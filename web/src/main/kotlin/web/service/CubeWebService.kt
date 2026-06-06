package web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import common.mtg.AsFan
import common.mtg.CardCategory
import common.mtg.CardListParser
import common.mtg.CubeAnalytics
import common.mtg.CubeCard
import common.mtg.CubeDiff
import common.mtg.CardCombos
import common.mtg.DeckLegality
import common.mtg.MtgGlossary
import common.mtg.MtgCurrency
import common.mtg.MtgNames
import common.mtg.MtgColor
import common.mtg.PackGenerator
import common.mtg.Rarity
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.random.Random

/**
 * Web-surface twin of the bot's cube tooling. Same `common.mtg` maths and
 * pack generation, fed by the Scryfall search API over `java.net.http`
 * (matching [UtilsWebService]'s HTTP style) so the Discord command and the
 * web page can't drift on as-fan numbers or pack-dealing rules.
 */
@Service
class CubeWebService {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val jackson = ObjectMapper()

    /** The as-fan calculator: (type ÷ cube) × pack size. */
    fun asFan(total: Int, cubeSize: Int, packSize: Int): CubeResult<Double> =
        try {
            CubeResult.ok(AsFan.value(total, cubeSize, packSize))
        } catch (e: IllegalArgumentException) {
            CubeResult.error(e.message ?: "Invalid as-fan inputs.")
        }

    /**
     * Looks a single card up by (fuzzy) name via Scryfall's `/cards/named`,
     * for the web card-lookup tool — the website twin of `/card lookup`.
     */
    fun card(name: String): CubeResult<CardLookupView> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return CubeResult.error("Enter a card name to look up.")
        val url = "$NAMED_ENDPOINT?fuzzy=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "tobybot-web/1.0")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            when (response.statusCode()) {
                200 -> cardOf(jackson.readTree(response.body()))
                    ?.let { CubeResult.ok(lookupView(it)) }
                    ?: CubeResult.error("Scryfall returned an unexpected card.")
                404 -> CubeResult.error("No card found matching “$trimmed”.")
                else -> CubeResult.error("Scryfall returned ${response.statusCode()}.")
            }
        } catch (e: IOException) {
            CubeResult.error("Could not reach Scryfall: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CubeResult.error("Request interrupted.")
        }
    }

    /**
     * Looks a card up by (fuzzy) name and fetches its official rulings — the
     * website twin of `/card rulings`. Two calls: `/cards/named` to resolve the
     * card (and its `rulings_uri`), then a GET of that uri. A resolved card
     * with no rulings is a success with an empty list, not an error.
     */
    fun rulings(name: String): CubeResult<RulingsView> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return CubeResult.error("Enter a card name to look up rulings.")
        val url = "$NAMED_ENDPOINT?fuzzy=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
        return try {
            val response = scryfallGet(url)
            when (response.statusCode()) {
                200 -> {
                    val card = jackson.readTree(response.body())
                    val cardName = card.path("name").asText("").takeIf { it.isNotBlank() }
                        ?: return CubeResult.error("Scryfall returned an unexpected card.")
                    val scryfallUri = card.path("scryfall_uri").asText("").takeIf { it.isNotBlank() }
                    val rulingsUri = card.path("rulings_uri").asText("").takeIf { it.isNotBlank() }
                    val rulings = rulingsUri?.let { fetchRulings(it) } ?: emptyList()
                    CubeResult.ok(RulingsView(cardName, scryfallUri, rulings))
                }
                404 -> CubeResult.error("No card found matching “$trimmed”.")
                else -> CubeResult.error("Scryfall returned ${response.statusCode()}.")
            }
        } catch (e: IOException) {
            CubeResult.error("Could not reach Scryfall: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CubeResult.error("Request interrupted.")
        }
    }

    /** GETs and parses a `rulings_uri`; an unreachable/non-200 rulings call yields no rulings. */
    private fun fetchRulings(rulingsUri: String): List<RulingView> =
        try {
            val response = scryfallGet(rulingsUri)
            if (response.statusCode() == 200) rulingsOf(jackson.readTree(response.body())) else emptyList()
        } catch (e: IOException) {
            emptyList()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            emptyList()
        }

    /** Maps a Scryfall `/rulings` JSON tree into ruling views, skipping blank comments. */
    fun rulingsOf(root: JsonNode): List<RulingView> {
        val data = root.path("data")
        if (!data.isArray) return emptyList()
        return data.mapNotNull { node ->
            val comment = node.path("comment").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RulingView(node.path("published_at").asText(""), comment)
        }
    }

    /**
     * Finds the combos a card appears in via the Commander Spellbook variants
     * API — the website twin of `/card combos`. A reachable card with no combos
     * is a success with an empty list, not an error.
     */
    fun combos(name: String): CubeResult<CombosView> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return CubeResult.error("Enter a card name to find combos.")
        val query = URLEncoder.encode("card:\"$trimmed\"", StandardCharsets.UTF_8)
        val url = "$SPELLBOOK_ENDPOINT?q=$query&limit=$MAX_COMBOS&ordering=-popularity"
        return try {
            val response = scryfallGet(url)
            if (response.statusCode() != 200) {
                return CubeResult.error("Couldn't reach Commander Spellbook (${response.statusCode()}).")
            }
            CubeResult.ok(CombosView(trimmed, combosOf(jackson.readTree(response.body()))))
        } catch (e: IOException) {
            CubeResult.error("Could not reach Commander Spellbook: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CubeResult.error("Request interrupted.")
        }
    }

    /** Looks up a Magic set by code via Scryfall's `/sets/:code` — the web twin of `/mtg set`. */
    fun set(code: String): CubeResult<SetView> {
        val trimmed = code.trim().lowercase()
        if (trimmed.isEmpty()) return CubeResult.error("Enter a set code (e.g. vow).")
        val url = "$SETS_ENDPOINT/" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
        return try {
            val response = scryfallGet(url)
            when (response.statusCode()) {
                200 -> {
                    val node = jackson.readTree(response.body())
                    val name = node.path("name").asText("").takeIf { it.isNotBlank() }
                        ?: return CubeResult.error("Scryfall returned an unexpected set.")
                    CubeResult.ok(
                        SetView(
                            code = node.path("code").asText(trimmed).uppercase(),
                            name = name,
                            setType = node.path("set_type").asText("").replace('_', ' '),
                            releasedAt = node.path("released_at").asText("").takeIf { it.isNotBlank() },
                            cardCount = node.path("card_count").asInt(0),
                            iconUrl = node.path("icon_svg_uri").asText("").takeIf { it.isNotBlank() },
                            scryfallUri = node.path("scryfall_uri").asText("").takeIf { it.isNotBlank() },
                        )
                    )
                }
                404 -> CubeResult.error("No set found with code “$trimmed”.")
                else -> CubeResult.error("Scryfall returned ${response.statusCode()}.")
            }
        } catch (e: IOException) {
            CubeResult.error("Could not reach Scryfall: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CubeResult.error("Request interrupted.")
        }
    }

    /** Looks a keyword up in the built-in glossary (pure, no network) — the web twin of `/mtg rule`. */
    fun rule(term: String): CubeResult<RuleView> {
        if (term.trim().isEmpty()) return CubeResult.error("Enter a keyword (e.g. trample).")
        return MtgGlossary.lookup(term)
            ?.let { CubeResult.ok(RuleView(it.keyword, it.text)) }
            ?: CubeResult.error("No glossary entry for “${term.trim()}”. Try an evergreen keyword like trample or flying.")
    }

    /** Maps a Commander Spellbook variants response into combo views. */
    fun combosOf(root: JsonNode): List<ComboView> {
        val results = root.path("results")
        if (!results.isArray) return emptyList()
        return results.mapNotNull { node ->
            val id = node.path("id").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val uses = node.path("uses").mapNotNull { it.path("card").path("name").asText("").takeIf { n -> n.isNotBlank() } }
            val produces = node.path("produces").mapNotNull { it.path("feature").path("name").asText("").takeIf { n -> n.isNotBlank() } }
            if (uses.isEmpty() && produces.isEmpty()) return@mapNotNull null
            ComboView(id, uses, produces, CardCombos.comboUrl(id))
        }
    }

    /** A short GET against Scryfall with the standard headers and timeout. */
    private fun scryfallGet(url: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "tobybot-web/1.0")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    /** Maps a resolved card to the lookup view, resolving rarity + colours for display. */
    internal fun lookupView(sc: ScryfallCard): CardLookupView = CardLookupView(
        name = sc.card.name,
        imageUrl = sc.imageUrl,
        imageUrlLarge = sc.imageUrlLarge,
        imageUrlBack = sc.imageUrlBack,
        typeLine = sc.card.typeLine,
        manaValue = sc.card.manaValue,
        manaCost = sc.card.manaCost,
        rarity = sc.card.rarity?.let { Rarity.parse(it).displayName },
        colors = MtgColor.entries.filter { it in sc.card.colors }.map { it.displayName },
        oracleText = sc.card.oracleText,
        priceUsd = sc.card.priceUsd,
        priceEur = sc.card.priceEur,
        priceTix = sc.card.priceTix,
        legalFormats = sc.card.legalFormats,
    )

    /**
     * Compares two pasted card lists by name (the [CubeDiff] maths) — added,
     * removed and count-changed cards. Pure: no Scryfall, so it's instant.
     */
    fun diff(listA: String, listB: String): CubeResult<DiffData> {
        if (listA.length > MAX_LIST_LENGTH || listB.length > MAX_LIST_LENGTH) return CubeResult.error(TOO_LARGE)
        val a = CardListParser.parse(listA)
        val b = CardListParser.parse(listB)
        if (a.isEmpty() && b.isEmpty()) return CubeResult.error("Paste a card list into both sides to compare.")
        val d = CubeDiff.diff(a, b)
        fun lines(list: List<CubeDiff.Line>) = list.map { DiffLineView(it.name, it.from, it.to) }
        return CubeResult.ok(
            DiffData(lines(d.added), lines(d.removed), lines(d.changed), d.sizeA, d.sizeB)
        )
    }

    /** As-fan distribution of a Scryfall query's cards, no pack generation. */
    fun preview(query: String, packSize: Int): CubeResult<PreviewData> {
        if (packSize <= 0) return CubeResult.error("Pack size must be at least 1.")
        return when (val pool = fetchPool(query)) {
            is CubeResult.Failure -> CubeResult.error(pool.error)
            is CubeResult.Success -> CubeResult.ok(buildPreview(query.trim(), pool.value, packSize, emptyList()))
        }
    }

    /**
     * Like [preview] but the pool is the user's own pasted card list,
     * resolved against Scryfall, rather than a search query.
     */
    fun previewList(list: String, packSize: Int): CubeResult<PreviewData> {
        if (packSize <= 0) return CubeResult.error("Pack size must be at least 1.")
        return when (val resolved = resolveList(list)) {
            is CubeResult.Failure -> CubeResult.error(resolved.error)
            is CubeResult.Success -> CubeResult.ok(
                buildPreview("your list", resolved.value.cards, packSize, resolved.value.notFound, resolved.value.note)
            )
        }
    }

    /**
     * Checks a pasted decklist against a format (the [DeckLegality] maths) —
     * which cards are banned, not in the format, or restricted, and whether the
     * deck is legal overall. Resolves the list against Scryfall for the per-card
     * legality data.
     */
    fun checkLegality(list: String, format: String): CubeResult<LegalityData> {
        val formatKey = format.trim().lowercase()
        val display = CubeCard.FORMATS.firstOrNull { it.first == formatKey }
            ?: return CubeResult.error("Unknown format. Pick one of: " + CubeCard.FORMATS.joinToString(", ") { it.second } + ".")
        return when (val resolved = resolveList(list)) {
            is CubeResult.Failure -> CubeResult.error(resolved.error)
            is CubeResult.Success -> {
                val report = DeckLegality.check(resolved.value.cards.map { it.card }, formatKey)
                CubeResult.ok(
                    LegalityData(
                        format = display.second,
                        legal = report.legal,
                        total = report.total,
                        banned = report.banned,
                        notLegal = report.notLegal,
                        restricted = report.restricted,
                        unknown = report.unknown,
                        notFound = resolved.value.notFound,
                        note = resolved.value.note,
                    )
                )
            }
        }
    }

    /** Draws a cube from a Scryfall query and deals balanced packs. */
    fun generate(query: String, packCount: Int, packSize: Int, balanced: Boolean): CubeResult<GenerateData> =
        when (val pool = fetchPool(query)) {
            is CubeResult.Failure -> CubeResult.error(pool.error)
            is CubeResult.Success -> buildGenerate(query.trim(), pool.value, packCount, packSize, balanced, emptyList())
        }

    /** Like [generate] but deals from the user's own pasted card list. */
    fun generateList(list: String, packCount: Int, packSize: Int, balanced: Boolean): CubeResult<GenerateData> =
        when (val resolved = resolveList(list)) {
            is CubeResult.Failure -> CubeResult.error(resolved.error)
            is CubeResult.Success ->
                buildGenerate("your list", resolved.value.cards, packCount, packSize, balanced, resolved.value.notFound, resolved.value.note)
        }

    private fun buildPreview(
        label: String,
        pool: List<ScryfallCard>,
        packSize: Int,
        notFound: List<String>,
        note: String? = null,
    ): PreviewData = PreviewData(
        query = label,
        poolSize = pool.size,
        packSize = packSize,
        groups = groups(pool, packSize),
        analytics = analyticsView(pool.map { it.card }, packSize),
        notFound = notFound,
        note = note,
    )

    /** The shared cube report, mapped to JSON-friendly views (enum → displayName). */
    internal fun analyticsView(cards: List<CubeCard>, packSize: Int): AnalyticsView {
        val a = CubeAnalytics.analyze(cards, packSize)
        return AnalyticsView(
            curve = a.curve.map { CurveBucketView(it.label, it.count) },
            averageManaValue = a.averageManaValue,
            nonLandCount = a.nonLandCount,
            types = a.types.map { TypeCountView(it.type.displayName, it.count, it.asFan) },
            rarities = a.rarities.map { RarityCountView(it.rarity.displayName, it.count, it.asFan) },
            duplicates = a.duplicates.map { DuplicateView(it.name, it.count) },
            colorPairs = a.colorPairs.map { ColorPairView(it.pair, it.count) },
            colorPips = a.colorPips.map { ColorPipView(it.color, it.count) },
            totalValues = a.totalValues.map { TotalValueView(it.currency.code, it.currency.display, it.amount) },
            valueExtremes = MtgCurrency.entries.mapNotNull { currency ->
                CubeAnalytics.valueExtremes(cards, currency)?.let {
                    ValueExtremesView(
                        currency = currency.code,
                        display = currency.display,
                        mostName = it.mostValuable.name,
                        mostAmount = it.mostValuable.amount,
                        leastName = it.leastValuable.name,
                        leastAmount = it.leastValuable.amount,
                    )
                }
            },
        )
    }

    private fun buildGenerate(
        label: String,
        pool: List<ScryfallCard>,
        packCount: Int,
        packSize: Int,
        balanced: Boolean,
        notFound: List<String>,
        note: String? = null,
    ): CubeResult<GenerateData> {
        val cards = pool.map { it.card }
        val viewByName = pool.associate { it.card.name to it.toView() }
        return when (val packs = PackGenerator(Random.Default).generate(cards, packCount, packSize, balanced)) {
            is PackGenerator.Result.Failure -> CubeResult.error(packs.reason)
            is PackGenerator.Result.Success -> CubeResult.ok(
                GenerateData(
                    query = label,
                    poolSize = pool.size,
                    packCount = packCount,
                    packSize = packSize,
                    balanced = balanced,
                    packs = packs.value.packs.map { pack ->
                        pack.map { viewByName[it.name] ?: CardView(it.name, null, null, it.typeLine, it.manaValue) }
                    },
                    distribution = distribution(packs.value.cards, packSize),
                    notFound = notFound,
                    note = note,
                )
            )
        }
    }

    /** Builds an ordered, present-only category as-fan table. */
    fun distribution(pool: List<CubeCard>, packSize: Int): List<CategoryAsFan> {
        val counts = AsFan.categoryCounts(pool)
        val asFans = AsFan.distribution(pool, packSize)
        return CardCategory.entries
            .filter { counts[it] != null }
            .map { cat ->
                CategoryAsFan(cat.displayName, counts.getValue(cat), asFans.getValue(cat))
            }
    }

    /**
     * Like [distribution] but carries the actual cards (name + thumbnail)
     * in each bucket, alphabetised. Cards dedupe by name so a pool with
     * duplicates doesn't repeat one in the list; [CategoryGroup.count]
     * still reflects the raw bucket size.
     */
    fun groups(pool: List<ScryfallCard>, packSize: Int): List<CategoryGroup> {
        val asFans = AsFan.distribution(pool.map { it.card }, packSize)
        val byCategory = pool.groupBy { it.card.category }
        return CardCategory.entries
            .filter { byCategory[it] != null }
            .map { cat ->
                val bucket = byCategory.getValue(cat)
                // De-dupe by name so a pool with ten Forests shows one tile,
                // but carry the copy count onto that tile so it's not hidden.
                val counts = bucket.groupingBy { it.card.name }.eachCount()
                val cards = bucket
                    .distinctBy { it.card.name }
                    .sortedBy { it.card.name }
                    .map { it.toView().copy(count = counts.getValue(it.card.name)) }
                CategoryGroup(
                    category = cat.displayName,
                    count = bucket.size,
                    asFan = asFans.getValue(cat),
                    cards = cards,
                )
            }
    }

    /** Maps a Scryfall `/cards/search` JSON tree into cube cards. */
    fun parseCards(root: JsonNode): List<CubeCard> = parseScryfall(root).map { it.card }

    /**
     * Like [parseCards] but keeps each card's small thumbnail URL so the
     * web page can render images. Single-faced cards carry `image_uris`
     * directly; double-faced cards put images on the first face instead.
     */
    fun parseScryfall(root: JsonNode): List<ScryfallCard> {
        val data = root.path("data")
        if (!data.isArray) return emptyList()
        return data.mapNotNull { cardOf(it) }
    }

    /** Maps one Scryfall card object to a [ScryfallCard], or null if it has no name. */
    fun cardOf(node: JsonNode): ScryfallCard? {
        val name = node.path("name").asText("").takeIf { it.isNotBlank() } ?: return null
        val identity = node.path("color_identity").mapNotNull { it.asText(null) }
        val typeLine = node.path("type_line").asText("")
        return ScryfallCard(
            card = CubeCard(
                name = name,
                colors = MtgColor.parse(identity),
                isLand = CubeCard.isLandType(typeLine),
                typeLine = typeLine,
                manaValue = node.path("cmc").asDouble(0.0),
                rarity = node.path("rarity").asText("").takeIf { it.isNotBlank() },
                manaCost = manaCostOf(node),
                oracleText = oracleTextOf(node),
                imageUrlBack = backImageOf(node),
                priceUsd = priceOf(node, "usd"),
                priceEur = priceOf(node, "eur"),
                priceTix = priceOf(node, "tix"),
                legalFormats = CubeCard.legalFormatsOf { node.path("legalities").path(it).asText(null) },
                legalities = CubeCard.legalitiesOf { node.path("legalities").path(it).asText(null) },
            ),
            imageUrl = imageOf(node, "small"),
            imageUrlLarge = imageOf(node, "normal"),
            imageUrlBack = backImageOf(node),
            manaCost = manaCostOf(node),
        )
    }

    /**
     * A card image at the given Scryfall [size] (`small` ≈ 146×204 thumb,
     * `normal` ≈ 488×680 for the hover zoom), or null if Scryfall has none.
     * Single-faced cards carry `image_uris` directly; double-faced cards
     * put images on the first face instead.
     */
    private fun imageOf(node: JsonNode, size: String): String? {
        node.path("image_uris").path(size).asText("").takeIf { it.isNotBlank() }?.let { return it }
        val faces = node.path("card_faces")
        if (faces.isArray && faces.size() > 0) {
            faces[0].path("image_uris").path(size).asText("").takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * The back-face `normal` image for a double-faced card (transform / modal
     * DFC), or null. Only true two-faced cards carry per-face `image_uris`;
     * split, adventure and aftermath cards share one image, so they correctly
     * return null here (no spurious "flip" affordance).
     */
    private fun backImageOf(node: JsonNode): String? {
        val faces = node.path("card_faces")
        if (faces.isArray && faces.size() > 1) {
            faces[1].path("image_uris").path("normal").asText("").takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * The card's mana cost string (e.g. `{1}{R}{R}`). Single-faced cards carry
     * it directly; double-faced cards put it on the front face. Null when the
     * card has none (most lands).
     */
    private fun manaCostOf(node: JsonNode): String? {
        node.path("mana_cost").asText("").takeIf { it.isNotBlank() }?.let { return it }
        val faces = node.path("card_faces")
        if (faces.isArray && faces.size() > 0) {
            faces[0].path("mana_cost").asText("").takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** A Scryfall `prices` entry (e.g. "usd"), or null when absent/blank. */
    private fun priceOf(node: JsonNode, key: String): String? =
        node.path("prices").path(key).asText("").takeIf { it.isNotBlank() }

    /**
     * The card's rules text, or null. Single-faced cards carry `oracle_text`
     * directly; double-faced cards put it on each face, combined with the
     * face names.
     */
    private fun oracleTextOf(node: JsonNode): String? {
        node.path("oracle_text").asText("").takeIf { it.isNotBlank() }?.let { return it }
        val faces = node.path("card_faces")
        if (!faces.isArray) return null
        val parts = faces.mapNotNull { face ->
            val text = face.path("oracle_text").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            face.path("name").asText("").takeIf { it.isNotBlank() }?.let { "$it\n$text" } ?: text
        }
        return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun fetchPool(query: String): CubeResult<List<ScryfallCard>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return CubeResult.error("Enter a Scryfall search query (e.g. set:vow).")

        val cards = mutableListOf<ScryfallCard>()
        var url: String? = searchUrl(trimmed)
        var page = 0
        try {
            while (url != null && cards.size < MAX_CARDS && page < MAX_PAGES) {
                val response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "tobybot-web/1.0")
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                when (response.statusCode()) {
                    200 -> {}
                    404 -> return CubeResult.error("No cards matched that query.")
                    else -> return CubeResult.error("Scryfall returned ${response.statusCode()}.")
                }
                val root = jackson.readTree(response.body())
                cards.addAll(parseScryfall(root))
                url = if (root.path("has_more").asBoolean(false)) {
                    root.path("next_page").asText(null)
                } else {
                    null
                }
                page++
            }
        } catch (e: IOException) {
            return CubeResult.error("Could not reach Scryfall: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return CubeResult.error("Request interrupted.")
        }

        if (cards.isEmpty()) return CubeResult.error("No usable cards matched that query.")
        return CubeResult.ok(cards.take(MAX_CARDS))
    }

    private fun searchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return "$SEARCH_ENDPOINT?q=$encoded&unique=cards"
    }

    /**
     * Parses a pasted decklist into (name, count) entries. Delegates to the
     * shared [CardListParser] so the web tool and the Discord command agree
     * on the accepted format.
     */
    fun parseList(text: String): List<ListEntry> =
        CardListParser.parse(text).map { ListEntry(it.name, it.count) }

    /**
     * Resolves a pasted list into a card pool by looking the names up via
     * Scryfall's `/cards/collection` endpoint (batched, ≤75 per request).
     * Quantities expand the pool (`3 Forest` → three Forests); names that
     * don't resolve are reported in [ResolvedPool.notFound].
     */
    private fun resolveList(text: String): CubeResult<ResolvedPool> {
        if (text.length > MAX_LIST_LENGTH) return CubeResult.error(TOO_LARGE)
        val entries = parseList(text)
        if (entries.isEmpty()) return CubeResult.error("Paste at least one card name, one per line.")

        val fetched = mutableListOf<ScryfallCard>()
        // Look cards up by their front face: Scryfall's collection lookup
        // matches a single face, not the full "A // B" name. matchEntries
        // ties the full-name cards Scryfall returns back to the entries.
        // Cap how many distinct names we look up: the pool is capped at
        // MAX_CARDS anyway, so resolving more would just hammer Scryfall (one
        // POST per 75 names) and tie up the request thread for no benefit.
        // Names past the cap fall through to notFound.
        val requestNames = entries.map { MtgNames.requestName(it.name) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_CARDS)
        for (chunk in requestNames.chunked(COLLECTION_BATCH)) {
            when (val batch = fetchCollection(chunk)) {
                is CubeResult.Failure -> return CubeResult.error(batch.error)
                is CubeResult.Success -> fetched.addAll(batch.value)
            }
        }

        val matched = matchEntries(entries, fetched)
        if (matched.pool.isEmpty()) return CubeResult.error("None of those card names matched Scryfall. Check the spelling?")
        // Be transparent if we had to cap a very large pool rather than
        // silently dropping the overflow.
        val note = if (matched.pool.size > MAX_CARDS) {
            "Your list resolved to ${matched.pool.size} cards; only the first $MAX_CARDS were used."
        } else {
            null
        }
        return CubeResult.ok(ResolvedPool(matched.pool.take(MAX_CARDS), matched.notFound, note))
    }

    /**
     * Matches parsed list entries to fetched cards and expands quantities.
     * Each card is indexed by its full name AND each face, so a pasted
     * front-face name (e.g. "Archangel Avacyn") matches the full name
     * Scryfall returns for a transform card ("Archangel Avacyn // Avacyn,
     * the Purifier"). Entries that don't resolve go into [MatchResult.notFound].
     */
    fun matchEntries(entries: List<ListEntry>, cards: List<ScryfallCard>): MatchResult {
        val byKey = HashMap<String, ScryfallCard>()
        cards.forEach { card ->
            MtgNames.matchKeys(card.card.name).forEach { key -> byKey.putIfAbsent(key, card) }
        }
        val pool = mutableListOf<ScryfallCard>()
        val notFound = mutableListOf<String>()
        for (entry in entries) {
            val card = byKey[MtgNames.lookupKey(entry.name)]
            if (card == null) notFound.add(entry.name) else repeat(entry.count) { pool.add(card) }
        }
        return MatchResult(pool, notFound.distinct())
    }

    /** One `/cards/collection` POST for up to 75 names. */
    private fun fetchCollection(names: List<String>): CubeResult<List<ScryfallCard>> {
        val identifiers = names.joinToString(",") { """{"name":${jackson.writeValueAsString(it)}}""" }
        val body = """{"identifiers":[$identifiers]}"""
        return try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(COLLECTION_ENDPOINT))
                    .header("User-Agent", "tobybot-web/1.0")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() != 200) {
                return CubeResult.error("Scryfall returned ${response.statusCode()} resolving your list.")
            }
            CubeResult.ok(parseScryfall(jackson.readTree(response.body())))
        } catch (e: IOException) {
            CubeResult.error("Could not reach Scryfall: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CubeResult.error("Request interrupted.")
        }
    }

    private companion object {
        const val SEARCH_ENDPOINT = "https://api.scryfall.com/cards/search"
        const val COLLECTION_ENDPOINT = "https://api.scryfall.com/cards/collection"
        const val NAMED_ENDPOINT = "https://api.scryfall.com/cards/named"
        const val SETS_ENDPOINT = "https://api.scryfall.com/sets"
        const val SPELLBOOK_ENDPOINT = "https://backend.commanderspellbook.com/variants/"
        const val MAX_COMBOS = 5
        const val MAX_CARDS = 750
        const val MAX_PAGES = 10
        const val COLLECTION_BATCH = 75

        // Bound pasted-list text on the compute endpoints (preview / generate /
        // diff) the way the controller already bounds saved/shared text, so an
        // unauthenticated POST can't force a huge parse/allocation.
        const val MAX_LIST_LENGTH = 100_000
        const val TOO_LARGE = "That list is too large (max 100,000 characters)."
    }
}

sealed interface CubeResult<out T> {
    data class Success<T>(val value: T) : CubeResult<T>
    data class Failure(val error: String) : CubeResult<Nothing>

    companion object {
        fun <T> ok(value: T): CubeResult<T> = Success(value)
        fun error(message: String): CubeResult<Nothing> = Failure(message)
    }
}

data class CategoryAsFan(val category: String, val count: Int, val asFan: Double)

/** A card paired with its Scryfall images, kept only inside the service. */
data class ScryfallCard(
    val card: CubeCard,
    val imageUrl: String?,
    val imageUrlLarge: String?,
    val imageUrlBack: String? = null,
    val manaCost: String? = null,
) {
    fun toView(): CardView =
        CardView(card.name, imageUrl, imageUrlLarge, card.typeLine, card.manaValue, manaCost, imageUrlBack, priceUsd = card.priceUsd)
}

/**
 * A single card the page renders: display name, small thumbnail, the larger
 * image used for the hover-to-enlarge preview, the stat line (type + mana
 * value), the mana cost, the back-face image for double-faced cards, and how
 * many copies sit in the pool (>1 only in the de-duped preview groups). Any
 * image or the mana cost may be null when Scryfall has none.
 */
data class CardView(
    val name: String,
    val imageUrl: String?,
    val imageUrlLarge: String?,
    val typeLine: String,
    val manaValue: Double,
    val manaCost: String? = null,
    val imageUrlBack: String? = null,
    val count: Int = 1,
    /** Scryfall USD market price (raw string, e.g. "1.50"), or null when unpriced. */
    val priceUsd: String? = null,
)

/** A colour/land bucket plus the actual cards it contains. */
data class CategoryGroup(
    val category: String,
    val count: Int,
    val asFan: Double,
    val cards: List<CardView>,
)

/** One parsed decklist line: a card name and how many copies to include. */
data class ListEntry(val name: String, val count: Int)

/** A resolved custom list: the card pool, unresolved names, and an optional note. */
data class ResolvedPool(
    val cards: List<ScryfallCard>,
    val notFound: List<String>,
    val note: String? = null,
)

/** Outcome of matching list entries to fetched cards: the pool + unresolved names. */
data class MatchResult(val pool: List<ScryfallCard>, val notFound: List<String>)

/** The "cube report" the preview renders: curve, types, rarity, averages, duplicates. */
data class CurveBucketView(val label: String, val count: Int)
data class TypeCountView(val type: String, val count: Int, val asFan: Double)
data class RarityCountView(val rarity: String, val count: Int, val asFan: Double)
data class DuplicateView(val name: String, val count: Int)
data class ColorPairView(val pair: String, val count: Int)
data class ColorPipView(val color: String, val count: Int)
data class AnalyticsView(
    val curve: List<CurveBucketView>,
    val averageManaValue: Double,
    val nonLandCount: Int,
    val types: List<TypeCountView>,
    val rarities: List<RarityCountView>,
    val duplicates: List<DuplicateView>,
    val colorPairs: List<ColorPairView>,
    val colorPips: List<ColorPipView>,
    /** Cube market value per currency (code, display name, amount), present currencies only. */
    val totalValues: List<TotalValueView> = emptyList(),
    /** Most/least valuable card per currency, present currencies only. */
    val valueExtremes: List<ValueExtremesView> = emptyList(),
)

/** One currency's summed cube value: Scryfall code ("usd"), display ("USD"), amount. */
data class TotalValueView(val currency: String, val display: String, val amount: Double)

/** The priciest and cheapest priced card in one currency. */
data class ValueExtremesView(
    val currency: String,
    val display: String,
    val mostName: String,
    val mostAmount: Double,
    val leastName: String,
    val leastAmount: Double,
)

/** A single card looked up by name: image plus its key facts. */
data class CardLookupView(
    val name: String,
    val imageUrl: String?,
    val imageUrlLarge: String?,
    val imageUrlBack: String?,
    val typeLine: String,
    val manaValue: Double,
    val manaCost: String?,
    val rarity: String?,
    val colors: List<String>,
    val oracleText: String? = null,
    /** Scryfall market prices (raw strings), or null when unpriced. */
    val priceUsd: String? = null,
    val priceEur: String? = null,
    val priceTix: String? = null,
    /** Play formats this card is currently legal in, display-cased, in [CubeCard.FORMATS] order. */
    val legalFormats: List<String> = emptyList(),
)

/** A card's official rulings, looked up by name: the card, its Scryfall page, and the notes. */
data class RulingsView(
    val name: String,
    val scryfallUri: String?,
    val rulings: List<RulingView>,
)

/** One published ruling: the ISO publish date and the note text. */
data class RulingView(val publishedAt: String, val comment: String)

/** The combos a card appears in, looked up by name. */
data class CombosView(val name: String, val combos: List<ComboView>)

/** One combo: its pieces, payoff, and Commander Spellbook link. */
data class ComboView(val id: String, val uses: List<String>, val produces: List<String>, val url: String)

/** A Magic set's headline facts, looked up by code. */
data class SetView(
    val code: String,
    val name: String,
    val setType: String,
    val releasedAt: String?,
    val cardCount: Int,
    val iconUrl: String?,
    val scryfallUri: String?,
)

/** A keyword glossary entry. */
data class RuleView(val keyword: String, val text: String)

/** The outcome of checking a pasted decklist against a format. */
data class LegalityData(
    val format: String,
    val legal: Boolean,
    val total: Int,
    val banned: List<String>,
    val notLegal: List<String>,
    val restricted: List<String>,
    val unknown: List<String>,
    val notFound: List<String> = emptyList(),
    val note: String? = null,
)

/** One card's change between two compared lists (copy counts from → to). */
data class DiffLineView(val name: String, val from: Int, val to: Int)

/** The outcome of comparing two card lists by name. */
data class DiffData(
    val added: List<DiffLineView>,
    val removed: List<DiffLineView>,
    val changed: List<DiffLineView>,
    val sizeA: Int,
    val sizeB: Int,
)

data class PreviewData(
    val query: String,
    val poolSize: Int,
    val packSize: Int,
    val groups: List<CategoryGroup>,
    val analytics: AnalyticsView,
    val notFound: List<String> = emptyList(),
    val note: String? = null,
)

data class GenerateData(
    val query: String,
    val poolSize: Int,
    val packCount: Int,
    val packSize: Int,
    val balanced: Boolean,
    val packs: List<List<CardView>>,
    val distribution: List<CategoryAsFan>,
    val notFound: List<String> = emptyList(),
    val note: String? = null,
)
