package common.notification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationChannelKindTest {

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
    fun `existing-behaviour channels default to opt-in to preserve current user experience`() {
        // These existed before the preference system was introduced — every
        // user got these DMs. Flipping defaults to opt-out would silently
        // break the bot for existing servers.
        listOf(
            NotificationChannelKind.DUEL_OFFER,
            NotificationChannelKind.TIP_RECEIVED,
            NotificationChannelKind.INTRO_PROMPT,
        ).forEach { kind ->
            assertTrue(kind.defaultOptIn, "${kind.name} must default to opt-in")
        }
    }

    @Test
    fun `noisy new channels default to opt-out so they don't spam existing users`() {
        listOf(
            NotificationChannelKind.LEVEL_UP_DM,
            NotificationChannelKind.STREAK_REMINDER,
            NotificationChannelKind.PRICE_ALERT,
        ).forEach { kind ->
            assertFalse(kind.defaultOptIn, "${kind.name} must default to opt-out")
        }
    }

    @Test
    fun `every kind ships a non-blank displayName and description`() {
        NotificationChannelKind.entries.forEach { kind ->
            assertNotNull(kind.displayName)
            assertTrue(kind.displayName.isNotBlank(), "${kind.name} displayName must not be blank")
            assertTrue(kind.description.isNotBlank(), "${kind.name} description must not be blank")
        }
    }
}
