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

    private fun card(name: String, typeLine: String, mv: Double, rarity: String?, land: Boolean = false) =
        CubeCard(name = name, isLand = land, typeLine = typeLine, manaValue = mv, rarity = rarity)

    private fun preview(pool: List<CubeCard>, packSize: Int = 5) = CubeEmbeds.previewEmbed(
        query = "test",
        poolSize = pool.size,
        packSize = packSize,
        counts = AsFan.categoryCounts(pool),
        distribution = AsFan.distribution(pool, packSize),
        analytics = CubeAnalytics.analyze(pool, packSize),
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
}
