package common.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationChannelKindTest {

    // ---- fromCode + display metadata (preserved from pre-refactor) ----

    @Test
    fun `fromCode is case-insensitive and resolves every enum value by name`() {
        NotificationChannelKind.entries.forEach { kind ->
            assertEquals(kind, NotificationChannelKind.fromCode(kind.name))
            assertEquals(kind, NotificationChannelKind.fromCode(kind.name.lowercase()))
        }
    }

    @Test
    fun `fromCode returns null for unknown codes`() {
        assertNull(NotificationChannelKind.fromCode("not_a_real_kind"))
        assertNull(NotificationChannelKind.fromCode(""))
    }

    @Test
    fun `every kind ships a non-blank displayName and description`() {
        NotificationChannelKind.entries.forEach { kind ->
            assertNotNull(kind.displayName)
            assertTrue(kind.displayName.isNotBlank(), "${kind.name} displayName must not be blank")
            assertTrue(kind.description.isNotBlank(), "${kind.name} description must not be blank")
        }
    }

    @Test
    fun `PRICE_ALERT description tells the user it's an auto-trade receipt`() {
        // The string surfaces in /notify, the web API, and the
        // notification-preferences page — all three rely on the enum's
        // description as their source of truth. A stale "DM on large
        // price moves" copy misleads the user into thinking PRICE_ALERT
        // is a heads-up rather than a receipt of a trade the bot just
        // ran on their behalf.
        val desc = NotificationChannelKind.PRICE_ALERT.description.lowercase()
        assertTrue(
            desc.contains("/pricealert") || desc.contains("pricealert"),
            "PRICE_ALERT description must point users at the /pricealert " +
                    "command source: ${NotificationChannelKind.PRICE_ALERT.description}"
        )
        assertTrue(
            desc.contains("buy") && desc.contains("sell"),
            "PRICE_ALERT description must mention both buy and sell so " +
                    "the user understands the receipt scope: " +
                    NotificationChannelKind.PRICE_ALERT.description
        )
    }

    // ---- per-surface defaults (new shape) ----

    @Test
    fun `every kind declares at least one supported surface`() {
        NotificationChannelKind.entries.forEach { kind ->
            assertTrue(
                kind.supportedSurfaces.isNotEmpty(),
                "${kind.name} must support at least one surface; shipping an orphan kind is a bug"
            )
        }
    }

    @Test
    fun `defaultOptIn returns false for any surface outside supportedSurfaces`() {
        NotificationChannelKind.entries.forEach { kind ->
            val unsupported = Surface.entries.toSet() - kind.supportedSurfaces
            unsupported.forEach { surface ->
                assertFalse(
                    kind.defaultOptIn(surface),
                    "${kind.name}.defaultOptIn($surface) must be false when not supported"
                )
            }
        }
    }

    @Test
    fun `supports(surface) returns true iff surface is in supportedSurfaces`() {
        NotificationChannelKind.entries.forEach { kind ->
            Surface.entries.forEach { surface ->
                assertEquals(
                    surface in kind.supportedSurfaces, kind.supports(surface),
                    "${kind.name}.supports($surface) inconsistent with supportedSurfaces"
                )
            }
        }
    }

    // ---- CHANNEL defaults preserve pre-refactor pinging behaviour ----

    @Test
    fun `channel-pinging kinds default CHANNEL=true so existing recipients keep getting pinged`() {
        // Pre-refactor, every notifier that put `<@id>` in setContent
        // pinged unconditionally. Per-surface refactor mustn't silently
        // flip that to opt-in — it would silence every existing user's
        // tip/duel/lottery-winner pings overnight.
        listOf(
            NotificationChannelKind.DUEL_OFFER,
            NotificationChannelKind.TIP_RECEIVED,
            NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET,
        ).forEach { kind ->
            assertTrue(kind.supports(Surface.CHANNEL), "${kind.name} must support CHANNEL")
            assertTrue(
                kind.defaultOptIn(Surface.CHANNEL),
                "${kind.name} CHANNEL default must stay true"
            )
        }
    }

    @Test
    fun `ACHIEVEMENT_UNLOCK supports all three surfaces with DM and CHANNEL default on, PUSH off`() {
        val k = NotificationChannelKind.ACHIEVEMENT_UNLOCK
        assertEquals(setOf(Surface.DM, Surface.CHANNEL, Surface.PUSH), k.supportedSurfaces)
        assertTrue(k.defaultOptIn(Surface.DM))
        assertTrue(k.defaultOptIn(Surface.CHANNEL))
        assertFalse(k.defaultOptIn(Surface.PUSH))
    }

    @Test
    fun `INTRO_PROMPT is DM-only with no CHANNEL or PUSH surface`() {
        // The intro prompt opens an interactive EventWaiter flow.
        // Doesn't make sense as a fire-and-forget push or a public
        // channel post.
        val k = NotificationChannelKind.INTRO_PROMPT
        assertEquals(setOf(Surface.DM), k.supportedSurfaces)
        assertTrue(k.defaultOptIn(Surface.DM))
    }

    // ---- DM-only legacy kinds — preserve old defaultOptIn values ----

    @Test
    fun `STREAK_REMINDER, PRICE_ALERT, LEVEL_UP default DM=false (matches pre-refactor defaults)`() {
        listOf(
            NotificationChannelKind.STREAK_REMINDER,
            NotificationChannelKind.PRICE_ALERT,
            NotificationChannelKind.LEVEL_UP,
        ).forEach { kind ->
            assertTrue(kind.supports(Surface.DM), "${kind.name} must support DM")
            assertFalse(
                kind.defaultOptIn(Surface.DM),
                "${kind.name} DM default must stay false to preserve pre-refactor opt-out"
            )
        }
    }

    @Test
    fun `LEVEL_UP supports DM, CHANNEL and PUSH with CHANNEL default on (preserves embed ping)`() {
        val k = NotificationChannelKind.LEVEL_UP
        assertEquals(setOf(Surface.DM, Surface.CHANNEL, Surface.PUSH), k.supportedSurfaces)
        assertFalse(k.defaultOptIn(Surface.DM))
        assertTrue(k.defaultOptIn(Surface.CHANNEL))
        assertFalse(k.defaultOptIn(Surface.PUSH))
    }

    @Test
    fun `INTRO_PROMPT defaults DM=true (matches pre-refactor opt-in)`() {
        assertTrue(NotificationChannelKind.INTRO_PROMPT.defaultOptIn(Surface.DM))
    }

    // ---- PUSH defaults universally off — adapter PR can ship without surprises ----

    @Test
    fun `every kind that supports PUSH defaults PUSH=false`() {
        // No user has opted in to push (no adapter exists). When the
        // adapter ships, every user must explicitly opt in — a default-on
        // PUSH would spam everyone the instant the adapter goes live.
        NotificationChannelKind.entries
            .filter { it.supports(Surface.PUSH) }
            .forEach { kind ->
                assertFalse(
                    kind.defaultOptIn(Surface.PUSH),
                    "${kind.name} PUSH default must stay false"
                )
            }
    }
}
