package database.service

import common.notification.NotificationChannelKind
import database.dto.UserNotificationPrefDto
import database.persistence.UserNotificationPrefPersistence
import database.service.impl.DefaultUserNotificationPrefService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefaultUserNotificationPrefServiceTest {

    private val discordId = 1L
    private val guildId = 42L

    private lateinit var persistence: InMemoryUserNotificationPrefPersistence
    private lateinit var service: DefaultUserNotificationPrefService

    @BeforeEach
    fun setup() {
        persistence = InMemoryUserNotificationPrefPersistence()
        service = DefaultUserNotificationPrefService(persistence)
    }

    @Test
    fun `isOptedIn returns the per-kind default when the user has no row`() {
        // Existing-behaviour kinds default opt-in.
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.DUEL_OFFER))
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.TIP_RECEIVED))
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT))

        // Noisy new kinds default opt-out.
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.STREAK_REMINDER))
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.PRICE_ALERT))
    }

    @Test
    fun `setPref overrides the default and persists`() {
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, optIn = true)
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.STREAK_REMINDER))

        service.setPref(discordId, guildId, NotificationChannelKind.DUEL_OFFER, optIn = false)
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.DUEL_OFFER))
    }

    @Test
    fun `setPref upserts the same row on repeated calls`() {
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, optIn = true)
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, optIn = false)
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, optIn = true)

        val rows = service.listForUser(discordId, guildId)
            .filter { it.channelKind == NotificationChannelKind.STREAK_REMINDER.name }
        assertEquals(1, rows.size, "setPref must upsert, not insert duplicates")
        assertTrue(rows.single().optIn)
    }

    @Test
    fun `prefs are scoped to (user, guild) — a different guild does not inherit`() {
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, optIn = true)
        // Same user, different guild → falls back to the default (opt-out).
        assertFalse(service.isOptedIn(discordId, guildId = 999L, NotificationChannelKind.STREAK_REMINDER))
    }

    @Test
    fun `listForUser returns only explicit rows (not defaults)`() {
        // No rows yet → empty.
        assertTrue(service.listForUser(discordId, guildId).isEmpty())

        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, optIn = true)
        service.setPref(discordId, guildId, NotificationChannelKind.PRICE_ALERT, optIn = false)

        val rows = service.listForUser(discordId, guildId)
        assertEquals(2, rows.size)
        assertEquals(
            setOf(
                NotificationChannelKind.STREAK_REMINDER.name,
                NotificationChannelKind.PRICE_ALERT.name
            ),
            rows.map { it.channelKind }.toSet()
        )
    }

    @Test
    fun `get returns null for kinds with no explicit row even if their default is opt-in`() {
        // DUEL_OFFER defaults to opt-in but there's no row written.
        assertNull(service.get(discordId, guildId, NotificationChannelKind.DUEL_OFFER))
        // After explicit set, get returns the row.
        service.setPref(discordId, guildId, NotificationChannelKind.DUEL_OFFER, optIn = false)
        val row = service.get(discordId, guildId, NotificationChannelKind.DUEL_OFFER)
        assertNotNull(row)
        assertFalse(row!!.optIn)
    }

    // ---------- Fake ----------

    private class InMemoryUserNotificationPrefPersistence : UserNotificationPrefPersistence {
        private val rows = mutableMapOf<Triple<Long, Long, String>, UserNotificationPrefDto>()

        override fun get(discordId: Long, guildId: Long, channelKind: String): UserNotificationPrefDto? =
            rows[Triple(discordId, guildId, channelKind)]

        override fun listByUser(discordId: Long, guildId: Long): List<UserNotificationPrefDto> =
            rows.values.filter { it.discordId == discordId && it.guildId == guildId }

        override fun upsert(row: UserNotificationPrefDto): UserNotificationPrefDto {
            rows[Triple(row.discordId, row.guildId, row.channelKind)] = row
            return row
        }
    }
}
