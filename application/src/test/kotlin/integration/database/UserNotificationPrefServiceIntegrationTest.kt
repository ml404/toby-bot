package integration.database

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import common.notification.NotificationChannelKind
import common.notification.Surface
import database.configuration.TestDatabaseConfig
import database.service.user.UserNotificationPrefService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Integration test for [UserNotificationPrefService] backed by the real
 * Testcontainers Postgres. Implicitly validates V37
 * (`per_surface_notification_prefs`):
 *
 *   - Flyway applies V37 on context boot; if the migration fails the
 *     Spring context fails to start and every test here red-bars.
 *   - Inserts via `setPref(kind, surface, ...)` round-trip through the
 *     real JPA layer, exercising the schema's 4-column PK.
 *   - PK uniqueness is exercised by repeated upserts for the same
 *     `(user, guild, kind, surface)` — no DB-level duplicates.
 *   - Per-surface independence on the same kind verifies the schema's
 *     surface column actually discriminates rows.
 *
 * Test guildId / discordId are large fixed values so they don't collide
 * with seed data from other integration tests.
 */
@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class,
        TestAppConfig::class,
        TestBotConfig::class,
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserNotificationPrefServiceIntegrationTest {

    @Autowired
    lateinit var service: UserNotificationPrefService

    // Use a high discordId/guildId space so this test doesn't collide
    // with other integration tests' seed data.
    private val discordId = 9_000_001L
    private val guildId = 9_000_002L
    private val otherGuildId = 9_000_003L

    @AfterEach
    fun cleanup() {
        // Reset every (user, guild, kind, surface) row this test could
        // have written so reruns start clean.
        listOf(guildId, otherGuildId).forEach { g ->
            NotificationChannelKind.entries.forEach { kind ->
                kind.supportedSurfaces.forEach { surface ->
                    runCatching {
                        // setPref overwrites; we can't directly delete via the
                        // public API. Set every row back to its default so
                        // the next test sees a clean default-state.
                        service.setPref(discordId, g, kind, surface, kind.defaultOptIn(surface))
                    }
                }
            }
        }
    }

    @Test
    fun `isOptedIn returns the per-(kind, surface) default when no row exists`() {
        // Fresh user — no rows. Defaults apply.
        assertEquals(
            NotificationChannelKind.TIP_RECEIVED.defaultOptIn(Surface.CHANNEL),
            service.isOptedIn(discordId, guildId, NotificationChannelKind.TIP_RECEIVED, Surface.CHANNEL)
        )
        assertEquals(
            NotificationChannelKind.STREAK_REMINDER.defaultOptIn(Surface.DM),
            service.isOptedIn(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM)
        )
    }

    @Test
    fun `setPref writes a row Flyway-V37 can persist with the surface column`() {
        // Persisting this requires the surface column V37 added — if the
        // migration didn't apply, the JPA insert fails with a SQL
        // constraint violation and the test red-bars with a clear msg.
        service.setPref(discordId, guildId, NotificationChannelKind.TIP_RECEIVED, Surface.CHANNEL, optIn = false)

        // Reads round-trip with the correct surface discrimination.
        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.TIP_RECEIVED, Surface.CHANNEL))
    }

    @Test
    fun `same (kind) DM and CHANNEL rows are independent`() {
        // ACHIEVEMENT_UNLOCK supports DM + CHANNEL + PUSH. Opt out of
        // DM; CHANNEL stays at the default (true). Verifies the V37 PK
        // change actually discriminates rows by surface — pre-V37 these
        // would have collided on (discord_id, guild_id, channel_kind).
        service.setPref(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.DM, optIn = false)

        assertFalse(service.isOptedIn(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.DM))
        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.CHANNEL))
    }

    @Test
    fun `repeated setPref for the same (user, guild, kind, surface) upserts a single row`() {
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM, optIn = true)
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM, optIn = false)
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM, optIn = true)

        // V37 PK includes surface — repeated setPref hits the same row,
        // upserts cleanly. listForUser should NOT return duplicates.
        val streakRows = service.listForUser(discordId, guildId)
            .filter { it.channelKind == NotificationChannelKind.STREAK_REMINDER.name }
            .filter { it.surface == Surface.DM.name }
        assertEquals(
            1, streakRows.size,
            "Repeated upserts on the same 4-tuple must not create duplicate rows; " +
                "schema PK must include surface"
        )
        assertTrue(streakRows.single().optIn)
    }

    @Test
    fun `listForUser returns mixed-surface rows`() {
        service.setPref(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.DM, optIn = false)
        service.setPref(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.CHANNEL, optIn = false)
        service.setPref(discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.DM, optIn = true)

        val rows = service.listForUser(discordId, guildId)
        // We only assert about the rows we wrote — cleanup runs after.
        val mine = rows
            .filter { it.discordId == discordId && it.guildId == guildId }
            .associateBy { it.channelKind to it.surface }
        assertNotNull(mine[NotificationChannelKind.ACHIEVEMENT_UNLOCK.name to Surface.DM.name])
        assertNotNull(mine[NotificationChannelKind.ACHIEVEMENT_UNLOCK.name to Surface.CHANNEL.name])
        assertNotNull(mine[NotificationChannelKind.PRICE_ALERT.name to Surface.DM.name])
    }

    @Test
    fun `setPref rejects unsupported (kind, surface) with IllegalArgumentException`() {
        // INTRO_PROMPT is DM-only. CHANNEL would write an unsupported
        // surface row — the service guards against it before the DB.
        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.setPref(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.CHANNEL, optIn = true)
        }
        assertTrue(ex.message?.contains("INTRO_PROMPT") == true)
        assertTrue(ex.message?.contains("CHANNEL") == true)
        // No row written.
        assertNull(service.get(discordId, guildId, NotificationChannelKind.INTRO_PROMPT, Surface.CHANNEL))
    }

    @Test
    fun `prefs are scoped per-guild - setting in one guild doesn't bleed to another`() {
        service.setPref(discordId, guildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM, optIn = true)

        // Same user, different guild: still on the default.
        assertEquals(
            NotificationChannelKind.STREAK_REMINDER.defaultOptIn(Surface.DM),
            service.isOptedIn(discordId, otherGuildId, NotificationChannelKind.STREAK_REMINDER, Surface.DM)
        )
    }

    @Test
    fun `PUSH surface rows persist forward-compatibly for the future adapter PR`() {
        // No adapter is wired today, but users can opt in via the web
        // UI / /notify command. The row must survive a write-and-read.
        service.setPref(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.PUSH, optIn = true)

        assertTrue(service.isOptedIn(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.PUSH))
        val row = service.get(discordId, guildId, NotificationChannelKind.ACHIEVEMENT_UNLOCK, Surface.PUSH)
        assertNotNull(row)
        assertEquals(Surface.PUSH.name, row!!.surface)
    }
}
