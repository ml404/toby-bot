package common.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SurfaceTest {

    @Test
    fun `entries are DM, CHANNEL, PUSH in declaration order`() {
        // Declaration order drives auto-derived slash-command choices
        // and the column order in the web preferences matrix. Locking
        // it in keeps the surface-aware UI stable when someone reorders.
        assertEquals(
            listOf(Surface.DM, Surface.CHANNEL, Surface.PUSH),
            Surface.entries.toList()
        )
    }

    @Test
    fun `every entry name is uppercase ASCII (URL-safe for the API path segment)`() {
        // `/api/engagement/{guildId}/notifications/{kindCode}/{surfaceCode}`
        // — surfaceCode lands in a path segment, so non-ASCII or
        // case-variant names would break round-tripping.
        Surface.entries.forEach { surface ->
            val name = surface.name
            assertTrue(name.isNotEmpty(), "Surface name must be non-empty")
            assertTrue(
                name.all { it in 'A'..'Z' || it == '_' },
                "Surface.$name must be uppercase ASCII with underscores only"
            )
        }
    }
}
