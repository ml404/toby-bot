package common.mtg.scryfall

import common.mtg.CubeCard
import common.mtg.MtgColor

/**
 * Maps a Scryfall card object (seen through a [JsonAccessor]) into a [CubeCard],
 * the one place the field-by-field mapping lives. The bot and the web both feed
 * their own JSON tree through here so the two surfaces can't drift on which
 * fields a card carries or how a double-faced card is read.
 *
 * Double-faced cards (transform / modal / split / adventure) put their mana
 * cost and front image on the **first face** and their rules text on **each**
 * face; the helpers below encode those fallbacks once.
 */
object ScryfallCardMapper {

    /**
     * Builds a [CubeCard] from [node], or null when it has no usable name.
     * Colour comes from `color_identity` (the cube-sorting convention) and land
     * status from the front-face type line.
     *
     * [decorateFaceName] formats a double-faced card's face name when combining
     * the two faces' rules text — the only surface-specific choice (Discord
     * bolds it with markdown; the web shows it plain), kept as a parameter so
     * both keep their exact output. [frontImageUrl] is the front image the
     * caller wants on the card (the bot stores the `normal` size here; the web
     * keeps its images in a separate view and passes null).
     */
    fun toCubeCard(
        node: JsonAccessor,
        decorateFaceName: (String) -> String = { it },
        frontImageUrl: String? = null,
    ): CubeCard? {
        val name = node.string("name") ?: return null
        val typeLine = node.string("type_line") ?: ""
        return CubeCard(
            name = name,
            colors = MtgColor.parse(node.stringList("color_identity")),
            isLand = CubeCard.isLandType(typeLine),
            typeLine = typeLine,
            manaValue = node.double("cmc"),
            imageUrl = frontImageUrl,
            rarity = node.string("rarity"),
            manaCost = manaCost(node),
            oracleText = oracleText(node, decorateFaceName),
            imageUrlBack = backImageUrl(node),
            priceUsd = price(node, "usd"),
            priceEur = price(node, "eur"),
            priceTix = price(node, "tix"),
            legalFormats = CubeCard.legalFormatsOf { legality(node, it) },
            legalities = CubeCard.legalitiesOf { legality(node, it) },
        )
    }

    /**
     * The card's image at the given Scryfall [size] (`small`, `normal`, …), or
     * null. Single-faced cards carry `image_uris` directly; double-faced cards
     * put it on the first face.
     */
    fun frontImageUrl(node: JsonAccessor, size: String): String? =
        node.child("image_uris")?.string(size)
            ?: node.children("card_faces").firstOrNull()?.child("image_uris")?.string(size)

    /**
     * The back-face `normal` image for a double-faced card (transform / modal
     * DFC), or null. Only true two-faced cards carry per-face `image_uris`;
     * split / adventure cards share one image, so they correctly return null.
     */
    fun backImageUrl(node: JsonAccessor): String? =
        node.children("card_faces").takeIf { it.size >= 2 }
            ?.get(1)?.child("image_uris")?.string("normal")

    /** The card's `mana_cost`, falling back to the first face's, or null. */
    private fun manaCost(node: JsonAccessor): String? =
        node.string("mana_cost")
            ?: node.children("card_faces").firstOrNull()?.string("mana_cost")

    /** A Scryfall `prices` entry (e.g. "usd"), or null when absent/blank. */
    private fun price(node: JsonAccessor, key: String): String? =
        node.child("prices")?.string(key)

    /** A Scryfall `legalities` status for a format code, or null when absent. */
    private fun legality(node: JsonAccessor, format: String): String? =
        node.child("legalities")?.string(format)

    /**
     * The card's rules text, or null. Single-faced cards carry `oracle_text`
     * directly; double-faced cards put it on each face, combined (newline-
     * separated) with each face's [decorateFaceName]-formatted name.
     */
    private fun oracleText(node: JsonAccessor, decorateFaceName: (String) -> String): String? {
        node.string("oracle_text")?.let { return it }
        val faces = node.children("card_faces")
        if (faces.isEmpty()) return null
        val parts = faces.mapNotNull { face ->
            val text = face.string("oracle_text") ?: return@mapNotNull null
            face.string("name")?.let { "${decorateFaceName(it)}\n$text" } ?: text
        }
        return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
    }
}
