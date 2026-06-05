package bot.toby.command.commands.mtg

import common.mtg.CubeCard
import common.mtg.MtgColor
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class CubeEmbedsTest {

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
