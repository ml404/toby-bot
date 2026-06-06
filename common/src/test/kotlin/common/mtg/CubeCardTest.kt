package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CubeCardTest {

    @Test
    fun `isLandType recognises a plain land`() {
        assertTrue(CubeCard.isLandType("Basic Land — Forest"))
        assertTrue(CubeCard.isLandType("Land — Mountain Plains"))
        assertTrue(CubeCard.isLandType("Legendary Land"))
    }

    @Test
    fun `isLandType is false for non-lands`() {
        assertFalse(CubeCard.isLandType("Instant"))
        assertFalse(CubeCard.isLandType("Creature — Human Wizard"))
        assertFalse(CubeCard.isLandType("Artifact"))
    }

    @Test
    fun `isLandType judges a modal card by its front face, not the land back`() {
        // Legion's Landing // Adanto, the First Fort — an enchantment you cast,
        // not a land, even though the back face is a land.
        assertFalse(CubeCard.isLandType("Legendary Enchantment // Legendary Land"))
        // Search for Azcanta // Azcanta, the Sunken Ruin.
        assertFalse(CubeCard.isLandType("Legendary Enchantment // Legendary Land — Island"))
    }

    @Test
    fun `isLandType is true when the front face itself is a land`() {
        // Westvale Abbey // Ormendahl, Profane Prince — a land you play.
        assertTrue(CubeCard.isLandType("Land // Creature — Demon"))
    }

    @Test
    fun `isBasicType recognises the basics, snow-covered basics and Wastes`() {
        assertTrue(CubeCard.isBasicType("Basic Land — Forest"))
        assertTrue(CubeCard.isBasicType("Basic Snow Land — Island"))
        assertTrue(CubeCard.isBasicType("Basic Land")) // Wastes
    }

    @Test
    fun `isBasicType is false for non-basic lands and spells`() {
        assertFalse(CubeCard.isBasicType("Land — Mountain Plains")) // a dual is not basic
        assertFalse(CubeCard.isBasicType("Legendary Land"))
        assertFalse(CubeCard.isBasicType("Artifact"))
        // Judged by the front face, like isLandType.
        assertFalse(CubeCard.isBasicType("Legendary Enchantment // Basic Land"))
    }

    @Test
    fun `rarity and manaCost default to null and round-trip when set`() {
        val plain = CubeCard(name = "Placeholder")
        assertEquals(null, plain.rarity)
        assertEquals(null, plain.manaCost)
        val ragavan = CubeCard(name = "Ragavan", rarity = "mythic", manaCost = "{R}")
        assertEquals("mythic", ragavan.rarity)
        assertEquals("{R}", ragavan.manaCost)
    }

    @Test
    fun `prices and legalFormats default empty and round-trip when set`() {
        val plain = CubeCard(name = "Placeholder")
        assertEquals(null, plain.priceUsd)
        assertEquals(null, plain.priceEur)
        assertEquals(null, plain.priceTix)
        assertTrue(plain.legalFormats.isEmpty())
        val card = CubeCard(
            name = "Ragavan",
            priceUsd = "60.00",
            priceEur = "55.50",
            priceTix = "12.0",
            legalFormats = listOf("Modern", "Legacy"),
        )
        assertEquals("60.00", card.priceUsd)
        assertEquals("55.50", card.priceEur)
        assertEquals("12.0", card.priceTix)
        assertEquals(listOf("Modern", "Legacy"), card.legalFormats)
    }

    @Test
    fun `price returns the field for each currency`() {
        val card = CubeCard(name = "X", priceUsd = "1.50", priceEur = "1.20", priceTix = "0.03")
        assertEquals("1.50", card.price(MtgCurrency.USD))
        assertEquals("1.20", card.price(MtgCurrency.EUR))
        assertEquals("0.03", card.price(MtgCurrency.TIX))
        val bare = CubeCard(name = "Y")
        assertEquals(null, bare.price(MtgCurrency.USD))
        assertEquals(null, bare.price(MtgCurrency.EUR))
        assertEquals(null, bare.price(MtgCurrency.TIX))
    }

    @Test
    fun `legalFormatsOf keeps only legal formats, display-cased, in FORMATS order`() {
        val status = mapOf(
            "standard" to "not_legal",
            "modern" to "legal",
            "legacy" to "legal",
            "vintage" to "restricted",
            "commander" to "legal",
            "pauper" to "banned",
        )
        // FORMATS order is standard, pioneer, modern, legacy, vintage, pauper, commander.
        assertEquals(listOf("Modern", "Legacy", "Commander"), CubeCard.legalFormatsOf { status[it] })
    }

    @Test
    fun `legalFormatsOf is empty when nothing is legal or the map is unknown`() {
        assertTrue(CubeCard.legalFormatsOf { null }.isEmpty())
        assertTrue(CubeCard.legalFormatsOf { "banned" }.isEmpty())
    }

    @Test
    fun `legalitiesOf keeps the raw status per present format code`() {
        val status = mapOf("modern" to "legal", "legacy" to "banned", "vintage" to "restricted")
        val map = CubeCard.legalitiesOf { status[it] }
        assertEquals("legal", map["modern"])
        assertEquals("banned", map["legacy"])
        assertEquals("restricted", map["vintage"])
        assertFalse(map.containsKey("standard")) // absent codes are dropped
    }

    @Test
    fun `mono-coloured card maps to its colour bucket`() {
        assertEquals(
            CardCategory.RED,
            CubeCard(name = "Lightning Bolt", colors = setOf(MtgColor.RED)).category,
        )
        assertEquals(
            CardCategory.WHITE,
            CubeCard(name = "Swords to Plowshares", colors = setOf(MtgColor.WHITE)).category,
        )
    }

    @Test
    fun `two or more colours map to multicolour`() {
        assertEquals(
            CardCategory.MULTICOLOR,
            CubeCard(name = "Lightning Helix", colors = setOf(MtgColor.RED, MtgColor.WHITE)).category,
        )
        assertEquals(
            CardCategory.MULTICOLOR,
            CubeCard(
                name = "Niv-Mizzet Reborn",
                colors = MtgColor.entries.toSet(),
            ).category,
        )
    }

    @Test
    fun `no colours maps to colourless`() {
        assertEquals(
            CardCategory.COLORLESS,
            CubeCard(name = "Sol Ring", colors = emptySet()).category,
        )
    }

    @Test
    fun `a land is bucketed as land even when it has a colour identity`() {
        // A dual land taps for colours but is still "fixing", not a spell.
        val dual = CubeCard(
            name = "Sacred Foundry",
            colors = setOf(MtgColor.RED, MtgColor.WHITE),
            isLand = true,
            typeLine = "Land — Mountain Plains",
        )
        assertEquals(CardCategory.LAND, dual.category)
    }

    @Test
    fun `colourless land is still a land`() {
        assertEquals(
            CardCategory.LAND,
            CubeCard(name = "Wasteland", isLand = true).category,
        )
    }

    @Test
    fun `ofColor covers every colour`() {
        MtgColor.entries.forEach { color ->
            // Round-trips: a mono-card of this colour lands in ofColor's bucket.
            val card = CubeCard(name = color.displayName, colors = setOf(color))
            assertEquals(CardCategory.ofColor(color), card.category)
        }
    }

    @Test
    fun `defaults describe a colourless zero-cost non-land`() {
        val card = CubeCard(name = "Placeholder")
        assertEquals(CardCategory.COLORLESS, card.category)
        assertEquals(0.0, card.manaValue)
        assertEquals(false, card.isLand)
    }
}
