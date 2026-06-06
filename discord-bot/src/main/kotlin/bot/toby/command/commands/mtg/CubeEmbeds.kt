package bot.toby.command.commands.mtg

import common.discord.embed
import common.discord.field
import common.mtg.CardCombos
import common.mtg.CardRulings
import common.mtg.CardCategory
import common.mtg.CardListParser
import common.mtg.CubeAnalytics
import common.mtg.CubeCard
import common.mtg.DeckLegality
import common.mtg.MtgColor
import common.mtg.MtgCurrency
import common.mtg.Rarity
import database.dto.user.CubeListDto
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color
import java.nio.charset.StandardCharsets

/**
 * Embed + attachment factories for `/cube`. Keeps the visual grammar of
 * the other polished utility commands (`MemeEmbeds`, `RollEmbeds`) so the
 * MTG tool feels like part of the set.
 */
internal object CubeEmbeds {

    /** Magic "five-colour" gold — the cube/identity accent. */
    val OK_COLOR: Color = Color(199, 161, 79)
    val ERROR_COLOR: Color = Color(237, 66, 69)

    private const val AUTHOR = "🃏  Cube workshop" // 🃏

    /** Keep the "couldn't find" field under Discord's 1024-char field cap. */
    private const val NOT_FOUND_LIMIT = 1000

    fun asFanEmbed(typeCount: Int, cubeSize: Int, packSize: Int, value: Double): MessageEmbed =
        embed(color = OK_COLOR) {
            setAuthor(AUTHOR)
            setTitle("As-fan")
            setDescription(
                "**${format(value)}** of that card type per pack.\n" +
                    "*Expected copies a player opens in one booster.*"
            )
            field("Formula", "(type ÷ cube size) × pack size", inline = false)
            field("Maths", "($typeCount ÷ $cubeSize) × $packSize = ${format(value)}", inline = false)
        }

    /** As-fan breakdown of a whole fetched pool, plus the cube report — no pack generation. */
    fun previewEmbed(
        query: String,
        poolSize: Int,
        packSize: Int,
        counts: Map<CardCategory, Int>,
        distribution: Map<CardCategory, Double>,
        analytics: CubeAnalytics.Analytics,
        notFound: List<String> = emptyList(),
        note: String? = null,
        currency: MtgCurrency = MtgCurrency.DEFAULT,
        valueExtremes: CubeAnalytics.ValueExtremes? = null,
    ): MessageEmbed = embed(color = OK_COLOR) {
        setAuthor(AUTHOR)
        setTitle("Cube preview")
        setDescription(
            "Query `$query` → **$poolSize** cards, as-fan per **$packSize**-card pack." +
                note.orEmpty().let { if (it.isNotEmpty()) "\nℹ️ $it" else "" }
        )
        field("Distribution", distributionTable(counts, distribution), inline = false)
        // The "cube report": curve (skipped for an all-land pool), types, rarity.
        if (analytics.nonLandCount > 0) field("Mana curve", curveLine(analytics), inline = false)
        field("Card types", typeTable(analytics.types), inline = false)
        field("Rarity", rarityTable(analytics.rarities), inline = false)
        if (analytics.colorPairs.isNotEmpty()) field("Colour pairs", pairTable(analytics.colorPairs), inline = false)
        if (analytics.colorPips.isNotEmpty()) field("Colour pips", pipTable(analytics.colorPips), inline = false)
        cubeValue(analytics, currency)?.let { field("Cube value", it, inline = false) }
        valueExtremes?.let { field("Top & bottom value", valueExtremesBlock(it), inline = false) }
        addDuplicatesField(analytics.duplicates)
        addNotFoundField(notFound)
    }

    /** Summary of a generated set of packs. */
    fun generateEmbed(
        query: String,
        poolSize: Int,
        packCount: Int,
        packSize: Int,
        balanced: Boolean,
        selected: List<CubeCard>,
        counts: Map<CardCategory, Int>,
        distribution: Map<CardCategory, Double>,
        notFound: List<String> = emptyList(),
        note: String? = null,
        currency: MtgCurrency = MtgCurrency.DEFAULT,
    ): MessageEmbed = embed(color = OK_COLOR) {
        setAuthor(AUTHOR)
        setTitle("Generated $packCount packs of $packSize")
        setDescription(
            "Drew **${selected.size}** cards from the **$poolSize**-card pool matching `$query`." +
                (if (balanced) "\nAs-fan balanced across colours, colourless and lands." else "") +
                note.orEmpty().let { if (it.isNotEmpty()) "\nℹ️ $it" else "" }
        )
        field("As-fan per pack", distributionTable(counts, distribution), inline = false)
        // Total value of the cards actually dealt into the packs.
        valueLine(CubeAnalytics.totalValues(selected), currency)?.let { field("Packs value", it, inline = false) }
        addNotFoundField(notFound)
        setFooter("Full pack lists attached as a text file.")
    }

    /**
     * Adds a "couldn't find" field listing names Scryfall didn't resolve, so
     * a saved cube with a typo (or a card Scryfall doesn't know) is visible
     * rather than silently dropped. Stays within the 1024-char field limit.
     */
    private fun net.dv8tion.jda.api.EmbedBuilder.addNotFoundField(notFound: List<String>) {
        if (notFound.isEmpty()) return
        field("⚠️ Couldn't find ${notFound.size}", truncateField(notFound.joinToString(", ")), inline = false)
    }

    /**
     * Lists non-basic cards that appear more than once — a singleton cube
     * shouldn't have any — only when there are some, so a legal cube stays
     * clean.
     */
    private fun net.dv8tion.jda.api.EmbedBuilder.addDuplicatesField(duplicates: List<CubeAnalytics.Duplicate>) {
        if (duplicates.isEmpty()) return
        val joined = duplicates.joinToString(", ") { "${it.name} ×${it.count}" }
        field("⚠️ Duplicates ${duplicates.size}", truncateField(joined), inline = false)
    }

    /** Keeps a comma-joined field value under Discord's 1024-char field cap. */
    private fun truncateField(joined: String): String =
        if (joined.length <= NOT_FOUND_LIMIT) joined
        else joined.take(NOT_FOUND_LIMIT).substringBeforeLast(", ") + " … (+more)"

    /** Compact one-line mana curve plus the average mana value. */
    private fun curveLine(analytics: CubeAnalytics.Analytics): String =
        "`" + analytics.curve.joinToString(" ") { "${it.label}:${it.count}" } + "`" +
            "  ·  avg MV ${format(analytics.averageManaValue)}"

    /** A `type — count (as-fan)` table, in type order, present types only. */
    private fun typeTable(types: List<CubeAnalytics.TypeCount>): String =
        types.joinToString("\n") { "${it.type.displayName} — ${it.count} (${format(it.asFan)}/pack)" }
            .ifEmpty { "—" }

    /** A `rarity — count (as-fan)` table, common→mythic, present rarities only. */
    private fun rarityTable(rarities: List<CubeAnalytics.RarityCount>): String =
        rarities.joinToString("\n") { "${it.rarity.displayName} — ${it.count} (${format(it.asFan)}/pack)" }
            .ifEmpty { "—" }

    /** A `guild — count` table for the two-colour cards. */
    private fun pairTable(pairs: List<CubeAnalytics.ColorPairCount>): String =
        pairs.joinToString("\n") { "${it.pair} — ${it.count}" }

    /** Coloured mana pips as a compact one-liner: `White 42 · Blue 38 · …`. */
    private fun pipTable(pips: List<CubeAnalytics.ColorPipCount>): String =
        pips.joinToString(" · ") { "${it.color} ${it.count}" }

    /** Keep the saved-cube listing under Discord's 4096-char description cap. */
    private const val SAVED_LIST_LIMIT = 25

    /**
     * Lists the cubes a user has saved on the website, each with its card
     * count, so they can see what's available to `/cube preview` or
     * `/cube generate` without leaving Discord. Shows an empty-state nudge
     * when they have none saved yet.
     */
    fun savedCubesEmbed(saved: List<CubeListDto>): MessageEmbed = embed(color = OK_COLOR) {
        setAuthor(AUTHOR)
        setTitle("Your saved cubes")
        if (saved.isEmpty()) {
            setDescription(
                "You haven't saved any cubes yet. Build one on the website's " +
                    "Cube workshop, hit **Save**, then use it here with " +
                    "`/cube preview saved:` or `/cube generate saved:`."
            )
            return@embed
        }
        val shown = saved.take(SAVED_LIST_LIMIT)
        val lines = shown.joinToString("\n") { dto ->
            val count = CardListParser.parse(dto.cards).sumOf { it.count }
            "• **${dto.name}** — $count card${if (count == 1) "" else "s"}"
        }
        val more = if (saved.size > shown.size) "\n…and ${saved.size - shown.size} more." else ""
        setDescription(lines + more)
        setFooter("Use one with /cube preview saved: or /cube generate saved:")
    }

    fun errorEmbed(message: String): MessageEmbed = embed(color = ERROR_COLOR) {
        setAuthor(AUTHOR)
        setTitle("Couldn't build that cube")
        setDescription(message)
    }

    /** A single-card panel for `/cube card`: image plus its key facts. */
    fun cardEmbed(card: CubeCard): MessageEmbed = embed(color = OK_COLOR) {
        setAuthor(AUTHOR)
        setTitle(card.name)
        card.imageUrl?.let { setImage(it) }
        val colours = if (card.colors.isEmpty()) "Colourless"
        else MtgColor.entries.filter { it in card.colors }.joinToString(", ") { it.displayName }
        val facts = buildList {
            if (card.typeLine.isNotBlank()) add("**Type** · ${card.typeLine}")
            add("**Mana value** · ${formatMv(card.manaValue)}")
            card.rarity?.let { add("**Rarity** · ${Rarity.parse(it).displayName}") }
            add("**Colour identity** · $colours")
            priceLine(card)?.let { add("**Price** · $it") }
            if (card.legalFormats.isNotEmpty()) add("**Legal** · ${card.legalFormats.joinToString(", ")}")
        }.joinToString("\n")
        val oracle = card.oracleText?.let { "\n\n${oracleBlock(it)}" }.orEmpty()
        setDescription(facts + oracle)
    }

    /**
     * The cube's total value line in the requested [currency]
     * (`≈ $123.45 (priced cards, USD)`), falling back to the first currency
     * anything in the pool is priced in when the chosen one has no prices, or
     * null when nothing in the pool is priced at all.
     */
    fun cubeValue(analytics: CubeAnalytics.Analytics, currency: MtgCurrency): String? =
        valueLine(analytics.totalValues, currency)

    /**
     * A `≈ $123.45 (priced cards, USD)` line from a per-currency total list,
     * preferring [currency] but falling back to the first currency anything is
     * priced in, or null when nothing is priced at all.
     */
    fun valueLine(totalValues: List<CubeAnalytics.TotalValue>, currency: MtgCurrency): String? {
        val total = totalValues.firstOrNull { it.currency == currency }?.let { currency to it.amount }
            ?: totalValues.firstOrNull()?.let { it.currency to it.amount }
            ?: return null
        val (cur, amount) = total
        return "≈ ${cur.symbol}${format(amount)}${cur.suffix} (priced cards, ${cur.display})"
    }

    /**
     * The deck-legality verdict for a format: a clear legal/illegal headline,
     * then the offending cards bucketed (banned / not in format / restricted),
     * each within the 1024-char field cap.
     */
    fun legalityEmbed(
        report: DeckLegality.Report,
        formatName: String,
        label: String,
        notFound: List<String> = emptyList(),
    ): MessageEmbed = embed(color = if (report.legal) OK_COLOR else ERROR_COLOR) {
        setAuthor(AUTHOR)
        setTitle(if (report.legal) "✅ Legal in $formatName" else "🚫 Not $formatName-legal")
        setDescription("Checked **${report.total}** cards from `$label` against **$formatName**.")
        if (report.banned.isNotEmpty()) field("⛔ Banned ${report.banned.size}", truncateField(report.banned.joinToString(", ")), inline = false)
        if (report.notLegal.isNotEmpty()) field("🚫 Not in format ${report.notLegal.size}", truncateField(report.notLegal.joinToString(", ")), inline = false)
        if (report.restricted.isNotEmpty()) field("⚠️ Restricted (max 1) ${report.restricted.size}", truncateField(report.restricted.joinToString(", ")), inline = false)
        if (report.legal && report.restricted.isEmpty()) {
            setFooter("Every card is legal in $formatName.")
        }
        addNotFoundField(notFound)
    }

    /** A two-line "most / least valuable card" block in the extremes' currency. */
    fun valueExtremesBlock(ext: CubeAnalytics.ValueExtremes): String {
        fun line(v: CubeAnalytics.ValuedCard) =
            "${v.name} (${ext.currency.symbol}${format(v.amount)}${ext.currency.suffix})"
        return "**Most:** ${line(ext.mostValuable)}\n**Least:** ${line(ext.leastValuable)}"
    }

    /**
     * The card's market prices as a compact one-liner (`$1.50 · €1.20 · 0.03 tix`),
     * present currencies only, or null when Scryfall has no price for it.
     */
    fun priceLine(card: CubeCard): String? = buildList {
        card.priceUsd?.let { add("$$it") }
        card.priceEur?.let { add("€$it") }
        card.priceTix?.let { add("$it tix") }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /**
     * The official rulings panel for `/cube rulings`: each ruling as a
     * `> date — comment` block, oldest first, trimmed to stay within an embed
     * description. Shows a friendly empty state when the card has no rulings.
     */
    fun rulingsEmbed(rulings: CardRulings): MessageEmbed = embed(color = OK_COLOR) {
        setAuthor(AUTHOR)
        setTitle("${rulings.cardName} — rulings")
        rulings.scryfallUri?.let { setUrl(it) }
        if (rulings.rulings.isEmpty()) {
            setDescription("No official rulings have been published for this card.")
            return@embed
        }
        setDescription(rulingsBlock(rulings.rulings))
        setFooter("${rulings.rulings.size} ruling${if (rulings.rulings.size == 1) "" else "s"} · source: Scryfall")
    }

    /**
     * The combos panel for `/cube combos`: one field per combo listing the
     * pieces it needs and what it produces, linked to Commander Spellbook.
     * Shows a friendly empty state when the card is in no known combos.
     */
    fun combosEmbed(combos: CardCombos): MessageEmbed = embed(color = OK_COLOR) {
        setAuthor(AUTHOR)
        setTitle("${combos.cardName} — combos")
        if (combos.combos.isEmpty()) {
            setDescription("No combos found for this card on Commander Spellbook.")
            return@embed
        }
        setDescription("Found **${combos.combos.size}** combo${if (combos.combos.size == 1) "" else "s"} using **${combos.cardName}**.")
        combos.combos.forEachIndexed { i, combo ->
            field("Combo ${i + 1}", comboField(combo), inline = false)
        }
        setFooter("Source: Commander Spellbook")
    }

    /** One combo as a `Cards: … → Produces: …` block with a Spellbook link, within the field cap. */
    private fun comboField(combo: CardCombos.Combo): String {
        val uses = combo.uses.joinToString(", ").ifEmpty { "—" }
        val produces = combo.produces.joinToString(", ").ifEmpty { "—" }
        return truncateField("**Cards:** $uses\n**Produces:** $produces\n[View combo](${combo.url})")
    }

    /**
     * Joins rulings into one description, each headed by its date, dropping
     * any that would overflow the embed description cap (so a heavily-ruled
     * card like a planeswalker doesn't blow the 4096-char limit).
     */
    fun rulingsBlock(rulings: List<CardRulings.Ruling>): String {
        val out = StringBuilder()
        var shown = 0
        for (r in rulings) {
            val head = r.publishedAt.takeIf { it.isNotBlank() }?.let { "**$it**\n" }.orEmpty()
            val entry = "$head${r.comment}"
            // +2 for the blank line between entries.
            if (out.length + entry.length + 2 > RULINGS_LIMIT) break
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(entry)
            shown++
        }
        if (shown < rulings.size) {
            val more = "\n\n…and ${rulings.size - shown} more (see Scryfall)."
            if (out.length + more.length <= RULINGS_DESC_CAP) out.append(more)
        }
        return out.toString()
    }

    /** Leave headroom under Discord's 4096-char description cap for the "…and N more" line. */
    private const val RULINGS_LIMIT = 3800
    private const val RULINGS_DESC_CAP = 4096

    /** Mana value without a trailing `.0` (whole numbers are the norm). */
    private fun formatMv(mv: Double): String =
        if (mv % 1.0 == 0.0) mv.toInt().toString() else format(mv)

    /** Oracle rules text, trimmed to stay well within an embed description. */
    fun oracleBlock(oracleText: String): String =
        if (oracleText.length <= ORACLE_LIMIT) oracleText else oracleText.take(ORACLE_LIMIT).trimEnd() + "…"

    private const val ORACLE_LIMIT = 1500

    /** A `category — count (as-fan)` table, in colour-pie order, present buckets only. */
    private fun distributionTable(
        counts: Map<CardCategory, Int>,
        distribution: Map<CardCategory, Double>,
    ): String = CardCategory.entries
        .filter { counts[it] != null }
        .joinToString("\n") { cat ->
            "${cat.displayName} — ${counts[cat]} (${format(distribution[cat] ?: 0.0)}/pack)"
        }
        .ifEmpty { "—" }

    /**
     * Renders the dealt packs as a plain-text file body. 24 packs of 15 is
     * far past what fits in an embed, so the full lists ride along as an
     * attachment.
     */
    fun packsFile(packs: List<List<CubeCard>>, currency: MtgCurrency = MtgCurrency.DEFAULT): ByteArray = buildString {
        packs.forEachIndexed { i, pack ->
            val priced = pack.mapNotNull { it.price(currency)?.toDoubleOrNull() }
            val packTotal = priced.takeIf { it.isNotEmpty() }
                ?.let { " — ≈ ${currency.symbol}${format(it.sum())}${currency.suffix}" }
                .orEmpty()
            appendLine("== Pack ${i + 1} (${pack.size} cards)$packTotal ==")
            pack.forEach { card ->
                val price = card.price(currency)?.let { " (${currency.symbol}$it${currency.suffix})" }.orEmpty()
                val image = card.imageUrl?.let { " — $it" }.orEmpty()
                appendLine("  ${card.name}$price$image")
            }
            appendLine()
        }
    }.toByteArray(StandardCharsets.UTF_8)

    private fun format(value: Double): String = String.format("%.2f", value)
}
