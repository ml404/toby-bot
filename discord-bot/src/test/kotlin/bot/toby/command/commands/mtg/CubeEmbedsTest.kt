package bot.toby.command.commands.mtg

import common.mtg.AsFan
import common.mtg.CubeAnalytics
import common.mtg.CubeCard
import common.mtg.MtgColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class CubeEmbedsTest {

    private fun card(
        name: String,
        typeLine: String,
        mv: Double,
        rarity: String?,
        land: Boolean = false,
        colors: Set<MtgColor> = emptySet(),
        manaCost: String? = null,
        priceUsd: String? = null,
        priceEur: String? = null,
        priceTix: String? = null,
    ) = CubeCard(
        name = name, colors = colors, isLand = land, typeLine = typeLine, manaValue = mv, rarity = rarity,
        manaCost = manaCost, priceUsd = priceUsd, priceEur = priceEur, priceTix = priceTix,
    )

    private fun preview(pool: List<CubeCard>, packSize: Int = 5, currency: common.mtg.MtgCurrency = common.mtg.MtgCurrency.USD) =
        CubeEmbeds.previewEmbed(
            query = "test",
            poolSize = pool.size,
            packSize = packSize,
            counts = AsFan.categoryCounts(pool),
            distribution = AsFan.distribution(pool, packSize),
            analytics = CubeAnalytics.analyze(pool, packSize),
            currency = currency,
        )

    private fun field(embed: net.dv8tion.jda.api.entities.MessageEmbed, name: String) =
        embed.fields.firstOrNull { it.name == name }

    @Test
    fun `previewEmbed carries the curve, type and rarity report fields`() {
        val pool = listOf(
            card("Bolt", "Instant", 1.0, "common"),
            card("Bear", "Creature — Bear", 2.0, "common"),
            card("Drake", "Creature — Drake", 3.0, "uncommon"),
            card("Forest", "Basic Land — Forest", 0.0, "common", land = true),
        )
        val embed = preview(pool)

        assertNotNull(field(embed, "Mana curve"))
        assertTrue(field(embed, "Mana curve")!!.value!!.contains("avg MV"))
        val types = field(embed, "Card types")
        assertNotNull(types)
        assertTrue(types!!.value!!.contains("Creature — 2"))
        assertTrue(types.value!!.contains("Land — 1"))
        assertTrue(field(embed, "Rarity")!!.value!!.contains("Common — 3"))
    }

    @Test
    fun `previewEmbed adds colour-pair and pip fields when the cube has multicolour cards`() {
        val pool = listOf(
            card("Teferi", "Creature", 3.0, "rare", colors = setOf(MtgColor.WHITE, MtgColor.BLUE), manaCost = "{1}{W}{U}"),
            card("Dovin", "Creature", 3.0, "rare", colors = setOf(MtgColor.WHITE, MtgColor.BLUE), manaCost = "{2}{W}{U}"),
            card("Bolt", "Instant", 1.0, "common", colors = setOf(MtgColor.RED), manaCost = "{R}"),
        )
        val embed = preview(pool)
        assertTrue(field(embed, "Colour pairs")!!.value!!.contains("Azorius (WU) — 2"))
        assertTrue(field(embed, "Colour pips")!!.value!!.contains("White 2"))
    }

    @Test
    fun `previewEmbed omits the colour-pair and pip fields for a colourless or costless cube`() {
        val pool = listOf(card("Sol Ring", "Artifact", 1.0, "uncommon"))
        val embed = preview(pool)
        assertNull(field(embed, "Colour pairs"))
        assertNull(field(embed, "Colour pips"))
    }

    @Test
    fun `previewEmbed stays within Discord's 25-field limit when fully loaded`() {
        // A pool that fires every conditional field: curve, types, rarity,
        // colour pairs, colour pips, duplicates — plus a notFound list.
        val pool = listOf(
            card("Teferi", "Creature", 3.0, "rare", colors = setOf(MtgColor.WHITE, MtgColor.BLUE), manaCost = "{1}{W}{U}"),
            card("Teferi", "Creature", 3.0, "rare", colors = setOf(MtgColor.WHITE, MtgColor.BLUE), manaCost = "{1}{W}{U}"),
            card("Bolt", "Instant", 1.0, "common", colors = setOf(MtgColor.RED), manaCost = "{R}"),
            card("Forest", "Basic Land — Forest", 0.0, "common", land = true),
        )
        val embed = CubeEmbeds.previewEmbed(
            query = "q", poolSize = pool.size, packSize = 5,
            counts = AsFan.categoryCounts(pool), distribution = AsFan.distribution(pool, 5),
            analytics = CubeAnalytics.analyze(pool, 5), notFound = listOf("Missing One"),
        )
        assertTrue(embed.fields.size <= 25, "previewEmbed emitted ${embed.fields.size} fields (Discord caps at 25)")
        assertTrue(embed.fields.any { it.name == "Colour pairs" })
        assertTrue(embed.fields.any { it.name == "Colour pips" })
    }

    @Test
    fun `previewEmbed shows a duplicates field only when a non-basic repeats`() {
        val singleton = listOf(
            card("Sol Ring", "Artifact", 1.0, "uncommon"),
            card("Forest", "Basic Land — Forest", 0.0, "common", land = true),
            card("Forest", "Basic Land — Forest", 0.0, "common", land = true), // basics allowed
        )
        assertNull(field(preview(singleton), "⚠️ Duplicates 1"))

        val withDupe = listOf(
            card("Sol Ring", "Artifact", 1.0, "uncommon"),
            card("Sol Ring", "Artifact", 1.0, "uncommon"),
        )
        val dupField = preview(withDupe).fields.firstOrNull { it.name!!.startsWith("⚠️ Duplicates") }
        assertNotNull(dupField)
        assertTrue(dupField!!.value!!.contains("Sol Ring ×2"))
    }

    @Test
    fun `previewEmbed skips the mana curve for an all-land pool`() {
        val pool = listOf(
            card("Forest", "Basic Land — Forest", 0.0, "common", land = true),
            card("Island", "Basic Land — Island", 0.0, "common", land = true),
        )
        val embed = preview(pool)
        assertNull(field(embed, "Mana curve"))
        // Types still render (both are lands).
        assertEquals("Land — 2 (5.00/pack)", field(embed, "Card types")!!.value)
    }

    @Test
    fun `cardEmbed shows the card's image and key facts`() {
        val card = CubeCard(
            name = "Ragavan, Nimble Pilferer",
            colors = setOf(MtgColor.RED),
            typeLine = "Legendary Creature — Monkey Pirate",
            manaValue = 1.0,
            imageUrl = "https://img/ragavan.jpg",
            rarity = "mythic",
            oracleText = "Whenever Ragavan deals combat damage, create a Treasure.",
        )
        val embed = CubeEmbeds.cardEmbed(card)

        assertEquals("Ragavan, Nimble Pilferer", embed.title)
        assertEquals("https://img/ragavan.jpg", embed.image?.url)
        val desc = embed.description!!
        assertTrue(desc.contains("Legendary Creature — Monkey Pirate"))
        assertTrue(desc.contains("Mana value** · 1"), "MV without trailing .0: $desc")
        assertTrue(desc.contains("Rarity** · Mythic"))
        assertTrue(desc.contains("Colour identity** · Red"))
        assertTrue(desc.contains("create a Treasure"), "oracle text shown: $desc")
    }

    @Test
    fun `cardEmbed describes a colourless card and omits rarity when unknown`() {
        val card = CubeCard(name = "Sol Ring", typeLine = "Artifact", manaValue = 1.0)
        val desc = CubeEmbeds.cardEmbed(card).description!!
        assertTrue(desc.contains("Colour identity** · Colourless"))
        assertFalse(desc.contains("Rarity"))
    }

    @Test
    fun `cardEmbed shows price and legality when present, omits them otherwise`() {
        val priced = CubeCard(
            name = "Ragavan, Nimble Pilferer",
            colors = setOf(MtgColor.RED),
            typeLine = "Legendary Creature — Monkey Pirate",
            manaValue = 1.0,
            rarity = "mythic",
            priceUsd = "60.00",
            priceEur = "55.50",
            priceTix = "12.00",
            legalFormats = listOf("Modern", "Legacy"),
        )
        val desc = CubeEmbeds.cardEmbed(priced).description!!
        assertTrue(desc.contains("Price** · \$60.00 · €55.50 · 12.00 tix"), "price line: $desc")
        assertTrue(desc.contains("Legal** · Modern, Legacy"), "legal line: $desc")

        val bare = CubeCard(name = "Token", typeLine = "Token", manaValue = 0.0)
        val bareDesc = CubeEmbeds.cardEmbed(bare).description!!
        assertFalse(bareDesc.contains("Price"))
        assertFalse(bareDesc.contains("Legal"))
    }

    @Test
    fun `colorIdentityLine lists colours in WUBRG order or Colourless when none`() {
        assertEquals(
            "White, Blue",
            CubeEmbeds.colorIdentityLine(CubeCard("Teferi", colors = setOf(MtgColor.BLUE, MtgColor.WHITE))),
        )
        assertEquals("Colourless", CubeEmbeds.colorIdentityLine(CubeCard("Sol Ring")))
    }

    @Test
    fun `priceLine joins present currencies only and is null when unpriced`() {
        assertEquals("\$1.50", CubeEmbeds.priceLine(CubeCard(name = "A", priceUsd = "1.50")))
        assertEquals(
            "\$1.50 · €1.20 · 0.03 tix",
            CubeEmbeds.priceLine(CubeCard(name = "B", priceUsd = "1.50", priceEur = "1.20", priceTix = "0.03")),
        )
        assertNull(CubeEmbeds.priceLine(CubeCard(name = "C")))
    }

    @Test
    fun `previewEmbed shows the cube's total value only when cards are priced`() {
        val priced = listOf(
            card("Bolt", "Instant", 1.0, "common", priceUsd = "2.00"),
            card("Bear", "Creature — Bear", 2.0, "common", priceUsd = "0.50"),
        )
        val value = field(preview(priced), "Cube value")
        assertNotNull(value)
        assertTrue(value!!.value!!.contains("$2.50"), "total value: ${value.value}")
        assertTrue(value.value!!.contains("USD"))

        assertNull(field(preview(listOf(card("Token", "Token", 0.0, null))), "Cube value"))
    }

    @Test
    fun `previewEmbed reports the cube value in the requested currency`() {
        val priced = listOf(
            card("Bolt", "Instant", 1.0, "common", priceUsd = "2.00", priceEur = "1.50", priceTix = "0.10"),
            card("Bear", "Creature — Bear", 2.0, "common", priceUsd = "0.50", priceEur = "0.40", priceTix = "0.02"),
        )
        val eur = field(preview(priced, currency = common.mtg.MtgCurrency.EUR), "Cube value")!!.value!!
        assertTrue(eur.contains("€1.90"), "EUR total: $eur")
        assertTrue(eur.contains("EUR"))

        val tix = field(preview(priced, currency = common.mtg.MtgCurrency.TIX), "Cube value")!!.value!!
        assertTrue(tix.contains("0.12 tix"), "Tix total: $tix")
    }

    @Test
    fun `cubeValue falls back to an available currency when the chosen one is unpriced`() {
        // Only USD prices exist, but the guild's default is EUR: show USD rather than nothing.
        val analytics = CubeAnalytics.analyze(listOf(card("Bolt", "Instant", 1.0, "common", priceUsd = "3.00")), 1)
        val line = CubeEmbeds.cubeValue(analytics, common.mtg.MtgCurrency.EUR)
        assertNotNull(line)
        assertTrue(line!!.contains("$3.00"), "fallback line: $line")
        assertTrue(line.contains("USD"))

        // Nothing priced at all → no line regardless of currency.
        assertNull(CubeEmbeds.cubeValue(CubeAnalytics.analyze(listOf(card("Token", "Token", 0.0, null)), 1), common.mtg.MtgCurrency.USD))
    }

    @Test
    fun `previewEmbed shows the most and least valuable cards when given extremes`() {
        val pool = listOf(
            card("Pricey", "Creature", 4.0, "mythic", priceUsd = "60.00"),
            card("Cheap", "Instant", 1.0, "common", priceUsd = "0.25"),
        )
        val extremes = CubeAnalytics.valueExtremes(pool, common.mtg.MtgCurrency.USD)
        val embed = CubeEmbeds.previewEmbed(
            query = "q", poolSize = pool.size, packSize = 2,
            counts = AsFan.categoryCounts(pool), distribution = AsFan.distribution(pool, 2),
            analytics = CubeAnalytics.analyze(pool, 2), valueExtremes = extremes,
        )
        val value = field(embed, "Top & bottom value")!!.value!!
        assertTrue(value.contains("Pricey ($60.00)"), "most: $value")
        assertTrue(value.contains("Cheap ($0.25)"), "least: $value")
    }

    @Test
    fun `previewEmbed omits the extremes field when none are given`() {
        assertNull(field(preview(listOf(card("Bolt", "Instant", 1.0, "common"))), "Top & bottom value"))
    }

    @Test
    fun `valueExtremesBlock formats most and least in the extremes currency`() {
        val ext = CubeAnalytics.ValueExtremes(
            common.mtg.MtgCurrency.EUR,
            CubeAnalytics.ValuedCard("Big", 55.5),
            CubeAnalytics.ValuedCard("Small", 0.4),
        )
        val block = CubeEmbeds.valueExtremesBlock(ext)
        assertTrue(block.contains("Big (€55.50)"), block)
        assertTrue(block.contains("Small (€0.40)"), block)
    }

    @Test
    fun `generateEmbed shows the packs value in the chosen currency`() {
        val selected = listOf(
            CubeCard("Bolt", setOf(MtgColor.RED), priceUsd = "2.00", priceEur = "1.50"),
            CubeCard("Bear", setOf(MtgColor.GREEN), priceUsd = "0.50", priceEur = "0.40"),
        )
        val embed = CubeEmbeds.generateEmbed(
            query = "q", poolSize = 10, packCount = 1, packSize = 2, balanced = true,
            selected = selected, counts = AsFan.categoryCounts(selected),
            distribution = AsFan.distribution(selected, 2), currency = common.mtg.MtgCurrency.EUR,
        )
        val value = field(embed, "Packs value")!!.value!!
        assertTrue(value.contains("€1.90"), "packs value: $value")
    }

    @Test
    fun `rulingsEmbed lists each ruling with its date, linking to Scryfall`() {
        val rulings = common.mtg.CardRulings(
            cardName = "Doubling Season",
            scryfallUri = "https://scryfall.com/card/dd",
            rulings = listOf(
                common.mtg.CardRulings.Ruling("2021-03-19", "Tokens are doubled."),
                common.mtg.CardRulings.Ruling("2022-01-01", "Counters are doubled too."),
            ),
        )
        val embed = CubeEmbeds.rulingsEmbed(rulings)
        assertEquals("Doubling Season — rulings", embed.title)
        assertEquals("https://scryfall.com/card/dd", embed.url)
        val desc = embed.description!!
        assertTrue(desc.contains("Tokens are doubled."))
        assertTrue(desc.contains("Counters are doubled too."))
        assertTrue(desc.contains("2021-03-19"))
        assertTrue(embed.footer!!.text!!.contains("2 rulings"))
    }

    @Test
    fun `rulingsEmbed shows an empty state when the card has no rulings`() {
        val rulings = common.mtg.CardRulings("Plains", "https://scryfall.com/p", emptyList())
        val embed = CubeEmbeds.rulingsEmbed(rulings)
        assertTrue(embed.description!!.contains("No official rulings"))
        assertNull(embed.footer)
    }

    @Test
    fun `combosEmbed lists each combo's pieces, payoff and link`() {
        val combos = common.mtg.CardCombos(
            "Kiki-Jiki, Mirror Breaker",
            listOf(
                common.mtg.CardCombos.Combo(
                    "7", listOf("Kiki-Jiki, Mirror Breaker", "Zealous Conscripts"),
                    listOf("Infinite haste creatures"), "https://commanderspellbook.com/combo/7/",
                ),
            ),
        )
        val embed = CubeEmbeds.combosEmbed(combos)
        assertEquals("Kiki-Jiki, Mirror Breaker — combos", embed.title)
        val field = embed.fields.first { it.name == "Combo 1" }.value!!
        assertTrue(field.contains("Zealous Conscripts"))
        assertTrue(field.contains("Infinite haste creatures"))
        assertTrue(field.contains("commanderspellbook.com/combo/7"))
    }

    @Test
    fun `combosEmbed shows an empty state when the card is in no combos`() {
        val embed = CubeEmbeds.combosEmbed(common.mtg.CardCombos("Plains", emptyList()))
        assertTrue(embed.description!!.contains("No combos found"))
        assertTrue(embed.fields.isEmpty())
    }

    @Test
    fun `setEmbed shows the set's headline facts`() {
        val set = common.mtg.MtgSet(
            code = "VOW", name = "Innistrad: Crimson Vow", setType = "expansion",
            releasedAt = "2021-11-19", cardCount = 277,
            iconUrl = "https://img/vow.svg", scryfallUri = "https://scryfall.com/sets/vow",
        )
        val embed = CubeEmbeds.setEmbed(set)
        assertEquals("Innistrad: Crimson Vow (VOW)", embed.title)
        assertEquals("https://scryfall.com/sets/vow", embed.url)
        val desc = embed.description!!
        assertTrue(desc.contains("Expansion"))
        assertTrue(desc.contains("2021-11-19"))
        assertTrue(desc.contains("277"))
    }

    @Test
    fun `watchAddedEmbed describes the watch and its target`() {
        val watch = database.dto.user.CardPriceWatchDto(
            id = 9, discordId = 7, cardName = "Ragavan", currency = "usd",
            direction = database.dto.user.CardPriceWatchDto.Direction.BELOW.name, threshold = 30.0,
        )
        val card = CubeCard("Ragavan, Nimble Pilferer", imageUrl = "https://img/r.jpg")
        val embed = CubeEmbeds.watchAddedEmbed(watch, card, common.mtg.MtgCurrency.USD, 45.0)
        val desc = embed.description!!
        assertTrue(desc.contains("below \$30.00"), desc)
        assertTrue(desc.contains("#9"))
        assertTrue(desc.contains("now \$45.00"), desc)
    }

    @Test
    fun `watchListEmbed lists watches or shows an empty state`() {
        val empty = CubeEmbeds.watchListEmbed(emptyList())
        assertTrue(empty.description!!.contains("not watching any"))

        val watches = listOf(
            database.dto.user.CardPriceWatchDto(id = 1, cardName = "Ragavan", currency = "usd", direction = "BELOW", threshold = 30.0),
            database.dto.user.CardPriceWatchDto(id = 2, cardName = "Mox", currency = "eur", direction = "ABOVE", threshold = 100.0),
        )
        val desc = CubeEmbeds.watchListEmbed(watches).description!!
        assertTrue(desc.contains("#1") && desc.contains("Ragavan") && desc.contains("below \$30.00"), desc)
        assertTrue(desc.contains("#2") && desc.contains("€100.00"), desc)
    }

    @Test
    fun `ruleEmbed shows the keyword and its reminder text`() {
        val embed = CubeEmbeds.ruleEmbed(common.mtg.MtgGlossary.Term("Trample", "This creature can deal excess combat damage…"))
        assertEquals("Trample", embed.title)
        assertTrue(embed.description!!.contains("excess combat damage"))
    }

    @Test
    fun `rulingsBlock drops overflow rulings and notes how many were hidden`() {
        // 60 long rulings can't all fit under the description cap — the block
        // must stop early and append an "…and N more" pointer.
        val many = (1..60).map { common.mtg.CardRulings.Ruling("2020-01-0$it", "x".repeat(200)) }
        val block = CubeEmbeds.rulingsBlock(many)
        assertTrue(block.length <= 4096, "block exceeded the embed description cap: ${block.length}")
        assertTrue(block.contains("more (see Scryfall)"), "expected an overflow pointer: $block")
    }

    @Test
    fun `legalityEmbed reports an illegal deck with banned and not-in-format buckets`() {
        val report = common.mtg.DeckLegality.check(
            listOf(
                CubeCard("Lightning Bolt", legalities = mapOf("modern" to "legal")),
                CubeCard("Lurrus", legalities = mapOf("modern" to "banned")),
                CubeCard("Black Lotus", legalities = mapOf("modern" to "not_legal")),
            ),
            "modern",
        )
        val embed = CubeEmbeds.legalityEmbed(report, "Modern", "my deck")
        assertTrue(embed.title!!.contains("Not Modern-legal"))
        assertEquals(CubeEmbeds.ERROR_COLOR.rgb, embed.color!!.rgb)
        assertTrue(field(embed, "⛔ Banned 1")!!.value!!.contains("Lurrus"))
        assertTrue(field(embed, "🚫 Not in format 1")!!.value!!.contains("Black Lotus"))
    }

    @Test
    fun `legalityEmbed reports a clean legal deck`() {
        val report = common.mtg.DeckLegality.check(
            listOf(CubeCard("Lightning Bolt", legalities = mapOf("modern" to "legal"))),
            "modern",
        )
        val embed = CubeEmbeds.legalityEmbed(report, "Modern", "my deck")
        assertTrue(embed.title!!.contains("Legal in Modern"))
        assertEquals(CubeEmbeds.OK_COLOR.rgb, embed.color!!.rgb)
        assertTrue(embed.footer!!.text!!.contains("Every card is legal"))
    }

    @Test
    fun `packsFile lists each card, appending its image link when present`() {
        val packs = listOf(
            listOf(
                CubeCard("Lightning Bolt", setOf(MtgColor.RED), imageUrl = "https://img/bolt.jpg"),
                CubeCard("Forest", isLand = true), // no image
            ),
        )
        val text = String(CubeEmbeds.packsFile(packs), StandardCharsets.UTF_8)

        assertTrue(text.contains("== Pack 1 (2 cards) =="))
        assertTrue(text.contains("  Lightning Bolt — https://img/bolt.jpg"))
        // A card with no image is just its name — no trailing dash.
        assertTrue(text.contains("  Forest\n"))
        assertFalse(text.contains("Forest —"))
    }

    @Test
    fun `packsFile includes per-card prices and a per-pack total in the chosen currency`() {
        val packs = listOf(
            listOf(
                CubeCard("Lightning Bolt", setOf(MtgColor.RED), imageUrl = "https://img/bolt.jpg", priceUsd = "2.00", priceEur = "1.50"),
                CubeCard("Forest", isLand = true, priceUsd = "0.10", priceEur = "0.08"),
            ),
        )
        val text = String(CubeEmbeds.packsFile(packs, common.mtg.MtgCurrency.EUR), StandardCharsets.UTF_8)

        // Pack header carries the EUR total (1.50 + 0.08).
        assertTrue(text.contains("== Pack 1 (2 cards) — ≈ €1.58 =="), "header: $text")
        // Each card shows its EUR price before the image link.
        assertTrue(text.contains("  Lightning Bolt (€1.50) — https://img/bolt.jpg"), "bolt line: $text")
        assertTrue(text.contains("  Forest (€0.08)"), "forest line: $text")
    }

    @Test
    fun `packsFile omits prices and the pack total when nothing is priced`() {
        val packs = listOf(listOf(CubeCard("Forest", isLand = true)))
        val text = String(CubeEmbeds.packsFile(packs), StandardCharsets.UTF_8)
        assertTrue(text.contains("== Pack 1 (1 cards) =="), text) // no " — ≈ " total
        assertFalse(text.contains("≈"))
    }
}
