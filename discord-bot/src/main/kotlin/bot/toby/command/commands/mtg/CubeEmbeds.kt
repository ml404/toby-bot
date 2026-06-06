package bot.toby.command.commands.mtg

import common.discord.embed
import common.discord.field
import common.mtg.CardCategory
import common.mtg.CardListParser
import common.mtg.CubeAnalytics
import common.mtg.CubeCard
import common.mtg.MtgColor
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
        analytics.totalValueUsd?.let { field("Cube value", "≈ $${format(it)} (priced cards, USD)", inline = false) }
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
    ): MessageEmbed = embed(color = OK_COLOR) {
        setAuthor(AUTHOR)
        setTitle("Generated $packCount packs of $packSize")
        setDescription(
            "Drew **${selected.size}** cards from the **$poolSize**-card pool matching `$query`." +
                (if (balanced) "\nAs-fan balanced across colours, colourless and lands." else "") +
                note.orEmpty().let { if (it.isNotEmpty()) "\nℹ️ $it" else "" }
        )
        field("As-fan per pack", distributionTable(counts, distribution), inline = false)
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
     * The card's market prices as a compact one-liner (`$1.50 · €1.20 · 0.03 tix`),
     * present currencies only, or null when Scryfall has no price for it.
     */
    fun priceLine(card: CubeCard): String? = buildList {
        card.priceUsd?.let { add("$$it") }
        card.priceEur?.let { add("€$it") }
        card.priceTix?.let { add("$it tix") }
    }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

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
    fun packsFile(packs: List<List<CubeCard>>): ByteArray = buildString {
        packs.forEachIndexed { i, pack ->
            appendLine("== Pack ${i + 1} (${pack.size} cards) ==")
            pack.forEach { card ->
                val image = card.imageUrl?.let { " — $it" }.orEmpty()
                appendLine("  ${card.name}$image")
            }
            appendLine()
        }
    }.toByteArray(StandardCharsets.UTF_8)

    private fun format(value: Double): String = String.format("%.2f", value)
}
