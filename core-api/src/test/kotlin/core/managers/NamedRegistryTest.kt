package core.managers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class NamedRegistryTest {

    private data class Thing(override val name: String) : Named

    private class ThingRegistry(override val items: List<Thing>) : NamedRegistry<Thing>

    @Test
    fun `findByName returns the entry whose name matches case-sensitively`() {
        val a = Thing("alpha")
        val b = Thing("beta")
        val registry = ThingRegistry(listOf(a, b))
        assertSame(a, registry.findByName("alpha"))
        assertSame(b, registry.findByName("beta"))
    }

    @Test
    fun `findByName ignores case`() {
        val a = Thing("Alpha")
        val registry = ThingRegistry(listOf(a))
        assertSame(a, registry.findByName("ALPHA"))
        assertSame(a, registry.findByName("alpha"))
        assertSame(a, registry.findByName("aLpHa"))
    }

    @Test
    fun `findByName returns null when no entry matches`() {
        val registry = ThingRegistry(listOf(Thing("alpha")))
        assertNull(registry.findByName("missing"))
        assertNull(registry.findByName(""))
    }

    @Test
    fun `findByName returns null over an empty registry`() {
        val registry = ThingRegistry(emptyList())
        assertNull(registry.findByName("anything"))
    }

    @Test
    fun `findByName returns the first match when names collide`() {
        val first = Thing("dup")
        val second = Thing("dup")
        val registry = ThingRegistry(listOf(first, second))
        assertSame(first, registry.findByName("dup"))
    }

    @Test
    fun `items reflects the underlying list`() {
        val list = listOf(Thing("a"), Thing("b"), Thing("c"))
        val registry = ThingRegistry(list)
        assertEquals(list, registry.items)
    }
}
