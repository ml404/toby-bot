package bot.toby.command.commands.mtg

import common.discord.embed
import common.discord.field
import common.mtg.CardCategory
import common.mtg.CardListParser
import common.mtg.CubeCard
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

    /** As-fan breakdown of a whole fetched pool — no pack generation. */
    fun previewEmbed(
        query: String,
        poolSize: Int,
        packSize: Int,
        counts: Map<CardCategory, Int>,
        distribution: Map<CardCategory, Double>,
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
        val joined = notFound.joinToString(", ")
        val value = if (joined.length <= NOT_FOUND_LIMIT) {
            joined
        } else {
            joined.take(NOT_FOUND_LIMIT).substringBeforeLast(", ") + " … (+more)"
        }
        field("⚠️ Couldn't find ${notFound.size}", value, inline = false)
    }

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
