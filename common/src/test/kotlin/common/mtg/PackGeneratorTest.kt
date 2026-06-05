package common.mtg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PackGeneratorTest {

    private fun pool(size: Int): List<CubeCard> =
        (1..size).map { CubeCard(name = "Card $it", colors = setOf(MtgColor.entries[it % 5])) }

    @Test
    fun `a pack count that would overflow Int fails cleanly instead of OOMing`() {
        // packCount × packSize as Int overflows: 2^30 × 4 wraps to 0, which
        // used to slip past the pool check into List(2^30) (an OOM). Long maths
        // must reject it as "not enough cards" — and return fast.
        val result = PackGenerator(Random(1)).generate(pool(10), packCount = 1_073_741_824, packSize = 4)
        val failure = assertInstanceOf(PackGenerator.Result.Failure::class.java, result)
        assertTrue(failure.reason.contains("Not enough cards"))
    }

    @Test
    fun `a near-Int-max pack size fails cleanly`() {
        val result = PackGenerator(Random(1)).generate(pool(10), packCount = 5, packSize = Int.MAX_VALUE)
        assertInstanceOf(PackGenerator.Result.Failure::class.java, result)
    }

    @Test
    fun `generates the requested number of packs each of the requested size`() {
        val result = PackGenerator(Random(1)).generate(pool(360), packCount = 24, packSize = 15)
        val success = assertInstanceOf(PackGenerator.Result.Success::class.java, result)
        assertEquals(24, success.value.packCount)
        assertTrue(success.value.packs.all { it.size == 15 })
        assertEquals(360, success.value.cards.size)
    }

    @Test
    fun `dealt cards are a subset of the pool with no duplicates`() {
        val source = pool(100)
        val result = PackGenerator(Random(7)).generate(source, packCount = 4, packSize = 15)
        val success = assertInstanceOf(PackGenerator.Result.Success::class.java, result)
        val dealt = success.value.cards
        assertEquals(60, dealt.size)
        assertEquals(60, dealt.toSet().size, "no card should be dealt twice")
        assertTrue(source.containsAll(dealt))
    }

    @Test
    fun `fails when the pool is too small`() {
        val result = PackGenerator().generate(pool(10), packCount = 24, packSize = 15)
        val failure = assertInstanceOf(PackGenerator.Result.Failure::class.java, result)
        assertTrue(failure.reason.contains("Not enough cards"))
    }

    @Test
    fun `fails on non-positive pack count or size`() {
        assertInstanceOf(
            PackGenerator.Result.Failure::class.java,
            PackGenerator().generate(pool(100), packCount = 0, packSize = 15),
        )
        assertInstanceOf(
            PackGenerator.Result.Failure::class.java,
            PackGenerator().generate(pool(100), packCount = 4, packSize = 0),
        )
    }

    @Test
    fun `pool exactly the needed size succeeds and uses every card`() {
        val source = pool(60)
        val result = PackGenerator(Random(3)).generate(source, packCount = 4, packSize = 15)
        val success = assertInstanceOf(PackGenerator.Result.Success::class.java, result)
        assertEquals(source.toSet(), success.value.cards.toSet())
    }

    @Test
    fun `balanced deal keeps each colour within one card across packs`() {
        // 5 colours, 50 of each = 250 cards; 5 packs of 15 → each pack
        // should hold ~3 of each colour (15 ÷ 5). Balanced dealing must
        // keep every pack's per-colour count within 1 of every other.
        val source = MtgColor.entries.flatMap { color ->
            (1..50).map { CubeCard("$color $it", colors = setOf(color)) }
        }
        val result = PackGenerator(Random(42)).generate(source, packCount = 5, packSize = 15, balanced = true)
        val success = assertInstanceOf(PackGenerator.Result.Success::class.java, result)

        MtgColor.entries.forEach { color ->
            val perPack = success.value.packs.map { pack ->
                pack.count { it.colors == setOf(color) }
            }
            val spread = perPack.max() - perPack.min()
            assertTrue(spread <= 1, "colour $color spread across packs was $spread ($perPack)")
        }
    }

    @Test
    fun `balanced deal levels lands across packs`() {
        // 30 lands + 120 mono-coloured spells → 150 cards; 10 packs of 15.
        // 30 lands ÷ 10 packs = 3 per pack, so a level deal gives each pack
        // 2–4 lands, never a land-flooded or land-light pack.
        val lands = (1..30).map { CubeCard("Land $it", isLand = true) }
        val spells = (1..120).map { CubeCard("Spell $it", colors = setOf(MtgColor.BLUE)) }
        val result = PackGenerator(Random(11))
            .generate(lands + spells, packCount = 10, packSize = 15, balanced = true)
        val success = assertInstanceOf(PackGenerator.Result.Success::class.java, result)

        val landsPerPack = success.value.packs.map { pack -> pack.count { it.isLand } }
        assertTrue(landsPerPack.max() - landsPerPack.min() <= 1, "land spread: $landsPerPack")
    }

    @Test
    fun `unbalanced deal still produces correctly sized packs`() {
        val result = PackGenerator(Random(5))
            .generate(pool(100), packCount = 4, packSize = 15, balanced = false)
        val success = assertInstanceOf(PackGenerator.Result.Success::class.java, result)
        assertTrue(success.value.packs.all { it.size == 15 })
        assertEquals(60, success.value.cards.size)
    }

    @Test
    fun `same seed produces the same packs`() {
        val a = PackGenerator(Random(99)).generate(pool(100), 4, 15)
        val b = PackGenerator(Random(99)).generate(pool(100), 4, 15)
        val sa = assertInstanceOf(PackGenerator.Result.Success::class.java, a)
        val sb = assertInstanceOf(PackGenerator.Result.Success::class.java, b)
        assertEquals(sa.value.packs, sb.value.packs)
    }
}
