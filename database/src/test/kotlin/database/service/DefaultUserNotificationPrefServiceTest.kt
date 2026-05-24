package database.service

import common.notification.NotificationChannelKind
import common.notification.Surface
import database.dto.user.UserNotificationPrefDto
import database.persistence.user.UserNotificationPrefPersistence
import database.service.user.impl.DefaultUserNotificationPrefService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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

    // ---- defaults (no explicit row) ----

    @Test
    fun `isOptedIn returns the per-(kind, surface) default when no explicit row exists`() {
        // Existing-behaviour: CHANNEL pinging kinds default opt-in.
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.DUEL_OFFER, Surface.CHANNEL))
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.TIP_RECEIVED, Surface.CHANNEL))
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.LOTTERY_DRAW_WITH_MY_TICKET, Surface.CHANNEL))
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.DM))
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.DM))

        // Noisy kinds default opt-out.
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM))
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.DM))

        // PUSH defaults universally off.
        NotificationChannelKind.entries
            .filter { it.supports(Surface.PUSH) }
            .forEach { kind ->
                assertFalse(
                    service.isOptedIn(discordId, guildId, kind, Surface.PUSH),
                    "${kind.name} PUSH default must be off"
                )
            }
    }

    @Test
    fun `isOptedIn for an unsupported (kind, surface) returns false (defensive)`() {
        // INTRO_PROMPT is DM-only — never throws when caller asks about CHANNEL.
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.CHANNEL))
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.PUSH))
        // TIP_RECEIVED has no DM surface.
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.TIP_RECEIVED, Surface.DM))
    }

    // ---- setPref / explicit override ----

    @Test
    fun `setPref persists and isOptedIn reflects it for the same surface`() {
        service.setPref(discordId, guildId, NotificationChannelKind.TIP_RECEIVED, Surface.CHANNEL, optIn = false)
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.TIP_RECEIVED, Surface.CHANNEL))

        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM, optIn = true)
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM))
    }

    @Test
    fun `setPref for the same kind on DM and CHANNEL keeps independent rows`() {
        // ACHIEVEMENT_UNLOCK supports both — opt-out DM, leave CHANNEL on.
        service.setPref(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.DM, optIn = false)
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.DM))
        // CHANNEL pref untouched — still defaults to opt-in.
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.CHANNEL))

        // Flip CHANNEL off — DM stays where we left it.
        service.setPref(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.CHANNEL, optIn = false)
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.CHANNEL))
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.DM))
    }

    @Test
    fun `setPref repeated calls upsert the same row, no duplicates`() {
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM, optIn = true)
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM, optIn = false)
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM, optIn = true)

        val rows = service.listForUser(discordId, guildId)
            .filter {
                it.channelKind == NotificationChannelKind.STREAK_REMINDER.name &&
                it.surface == Surface.DM.name
            }
        assertEquals(1, rows.size, "setPref must upsert per (user, guild, kind, surface) — no duplicate rows")
        assertTrue(rows.single().optIn)
    }

    @Test
    fun `setPref for an unsupported (kind, surface) throws IllegalArgumentException`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.setPref(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.CHANNEL, optIn = true)
        }
        val message = ex.message ?: ""
        assertTrue(message.contains("INTRO_PROMPT"), "message should name the offending kind")
        assertTrue(message.contains("CHANNEL"), "message should name the offending surface")
    }

    @Test
    fun `setPref for unsupported (kind, surface) does NOT touch persistence`() {
        runCatching {
            service.setPref(discordId, guildId, NotificationChannelKind.TIP_RECEIVED, Surface.DM, optIn = true)
        }
        assertTrue(
            service.listForUser(discordId, guildId).isEmpty(),
            "no rows should be written when setPref rejects"
        )
    }

    // ---- listForUser ----

    @Test
    fun `listForUser returns mixed-surface rows for the same kind`() {
        service.setPref(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.DM, optIn = false)
        service.setPref(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.CHANNEL, optIn = false)

        val rows = service.listForUser(discordId, guildId)
        assertEquals(2, rows.size)
        val bySurface = rows.associateBy { it.surface }
        assertEquals(false, bySurface[Surface.DM.name]?.optIn)
        assertEquals(false, bySurface[Surface.CHANNEL.name]?.optIn)
    }

    @Test
    fun `listForUser returns only explicit rows, never default-derived`() {
        assertTrue(service.listForUser(discordId, guildId).isEmpty())

        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM, optIn = true)
        service.setPref(discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.PUSH, optIn = true)

        val rows = service.listForUser(discordId, guildId)
        assertEquals(2, rows.size)
        assertEquals(
            setOf(
                NotificationChannelKind.STREAK_REMINDER.name to Surface.DM.name,
                NotificationChannelKind.PRICE_ALERT.name to Surface.PUSH.name,
            ),
            rows.map { it.channelKind to it.surface }.toSet()
        )
    }

    // ---- get ----

    @Test
    fun `get returns null until an explicit row exists, regardless of the default`() {
        // DUEL_OFFER CHANNEL defaults to true but the row doesn't exist.
        assertNull(service.get(discordId, guildId, NotificationChannelKind.DUEL_OFFER, Surface.CHANNEL))
        service.setPref(discordId, guildId, NotificationChannelKind.DUEL_OFFER, Surface.CHANNEL, optIn = false)
        val row = service.get(discordId, guildId, NotificationChannelKind.DUEL_OFFER, Surface.CHANNEL)
        assertNotNull(row)
        assertFalse(row!!.optIn)
        assertEquals(Surface.CHANNEL.name, row.surface)
    }

    // ---- per-guild scoping ----

    @Test
    fun `prefs are scoped per (user, guild) and per surface independently`() {
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM, optIn = true)
        // Same user different guild — falls back to default (opt-out).
        assertFalse(service.isOptedIn(discordId, guildId = 999L, NotificationChannelKind.STREAK_REMINDER, Surface.DM))
    }

    // ---- Fake persistence ----

    private class InMemoryUserNotificationPrefPersistence : UserNotificationPrefPersistence {
        private val rows = mutableMapOf<Quad, UserNotificationPrefDto>()

        private data class Quad(val a: Long, val b: Long, val c: String, val d: String)

        override fun get(
            discordId: Long, guildId: Long, channelKind: String, surface: String,
        ): UserNotificationPrefDto? = rows[Quad(discordId, guildId, channelKind, surface)]

        override fun listByUser(discordId: Long, guildId: Long): List<UserNotificationPrefDto> =
            rows.values.filter { it.discordId == discordId && it.guildId == guildId }

        override fun upsert(row: UserNotificationPrefDto): UserNotificationPrefDto {
            rows[Quad(row.discordId, row.guildId, row.channelKind, row.surface)] = row
            return row
        }
    }
}
