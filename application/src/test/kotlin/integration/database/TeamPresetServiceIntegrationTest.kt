package integration.database

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import database.service.TeamPresetService
import database.service.TeamSplitSessionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration

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
class TeamPresetServiceIntegrationTest {

    @Autowired
    lateinit var teamPresetService: TeamPresetService

    @Autowired
    lateinit var teamSplitSessionService: TeamSplitSessionService

    private val guildId = 999_001L

    @BeforeEach
    fun setUp() {
        teamPresetService.clearCache()
        teamPresetService.deleteAllForGuild(guildId)
        teamSplitSessionService.purgeOlderThan(Duration.ZERO)
    }

    @Test
    fun `upsertPreset creates a new preset and listForGuild surfaces it`() {
        val saved = teamPresetService.upsertPreset(
            guildId = guildId,
            name = "friday-night",
            memberIds = listOf(111L, 222L, 333L),
            createdByDiscordId = 42L,
        )
        assertNotNull(saved.id)
        assertEquals("friday-night", saved.name)
        assertEquals(listOf(111L, 222L, 333L), saved.memberIdList)

        teamPresetService.clearCache()
        val listed = teamPresetService.listForGuild(guildId)
        assertEquals(1, listed.size)
        assertEquals("friday-night", listed[0].name)
    }

    @Test
    fun `upsertPreset is case-insensitive and overwrites in place`() {
        teamPresetService.upsertPreset(guildId, "Crew", listOf(111L, 222L), 42L)
        teamPresetService.upsertPreset(guildId, "crew", listOf(333L, 444L, 555L), 42L)

        teamPresetService.clearCache()
        val all = teamPresetService.listForGuild(guildId)
        assertEquals(1, all.size, "case-insensitive upsert should not create a second row")
        assertEquals(listOf(333L, 444L, 555L), all[0].memberIdList)
    }

    @Test
    fun `getByName matches case-insensitively`() {
        teamPresetService.upsertPreset(guildId, "Squad", listOf(111L), 42L)
        assertNotNull(teamPresetService.getByName(guildId, "squad"))
        assertNotNull(teamPresetService.getByName(guildId, "SQUAD"))
        assertNull(teamPresetService.getByName(guildId, "other"))
    }

    @Test
    fun `split session roundtrips and can be marked confirmed`() {
        val session = teamSplitSessionService.createSession(
            guildId = guildId,
            requesterDiscordId = 42L,
            memberIds = listOf(111L, 222L, 333L, 444L),
            teamCount = 2,
            assignments = listOf(listOf(111L, 222L), listOf(333L, 444L)),
            teamNames = listOf("Red", "Blue"),
        )
        val fetched = teamSplitSessionService.getSession(session.id)
        assertNotNull(fetched)
        assertEquals(2, fetched!!.teamCount)

        val updated = teamSplitSessionService.markConfirmed(session.id)
        assertEquals("confirmed", updated!!.lastAction)
    }

    @Test
    fun `purgeOlderThan removes nothing when cutoff is in the past`() {
        teamSplitSessionService.createSession(
            guildId = guildId, requesterDiscordId = 42L,
            memberIds = listOf(111L, 222L), teamCount = 2,
            assignments = listOf(listOf(111L), listOf(222L)),
            teamNames = listOf("A", "B"),
        )
        // A 1-hour cutoff against a row that was just inserted should be a no-op.
        val deleted = teamSplitSessionService.purgeOlderThan(Duration.ofHours(1))
        assertEquals(0, deleted)
    }
}
