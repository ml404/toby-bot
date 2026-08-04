package database.persistence.impl

import database.persistence.music.impl.DefaultMusicFilePersistence
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The mapping half of the guild intro report.
 *
 * That the SQL is valid is proven against a real Postgres in
 * `GuildIntroStatsIntegrationTest` — `octet_length` and `COUNT(*) FILTER`
 * have no JPQL spelling, and the whole point of the query is to total the
 * size of the stored audio without loading it. What's asserted here is the
 * part that has bitten before: JDBC is free to hand back `COUNT` as Long,
 * `SUM` as BigInteger or BigDecimal, and an empty table as nulls, and a
 * straight cast would throw on a report nobody would then be able to read.
 */
class DefaultMusicFilePersistenceStatsTest {

    private lateinit var em: EntityManager
    private lateinit var query: Query
    private lateinit var persistence: DefaultMusicFilePersistence

    private val guildId = 4567L

    @BeforeEach
    fun setUp() {
        em = mockk(relaxed = true)
        query = mockk(relaxed = true)
        every { em.createNativeQuery(any<String>()) } returns query
        every { query.setParameter(any<String>(), any()) } returns query
        persistence = DefaultMusicFilePersistence().apply {
            DefaultMusicFilePersistence::class.java
                .getDeclaredField("entityManager")
                .apply { isAccessible = true }
                .set(this, em)
        }
    }

    private fun resultRow(vararg values: Any?) {
        every { query.singleResult } returns arrayOf(*values)
    }

    @Test
    fun `each column lands in the field it belongs to`() {
        resultRow(3L, 2L, 600L, 9L, 1L, 1L)

        val stats = persistence.getGuildIntroStats(guildId, 2)

        assertEquals(3L, stats.introCount)
        assertEquals(2L, stats.userCount)
        assertEquals(600L, stats.storedBytes)
        assertEquals(9L, stats.totalPlays)
        assertEquals(1L, stats.disabledCount)
        assertEquals(1L, stats.brokenCount)
    }

    @Test
    fun `sums returned as BigInteger or BigDecimal are still read as numbers`() {
        resultRow(BigInteger.valueOf(3), 2L, BigDecimal("600"), BigInteger.valueOf(9), 0L, 0L)

        val stats = persistence.getGuildIntroStats(guildId, 2)

        assertEquals(3L, stats.introCount)
        assertEquals(600L, stats.storedBytes)
        assertEquals(9L, stats.totalPlays)
    }

    @Test
    fun `a null column reads as zero rather than blowing up the report`() {
        resultRow(0L, 0L, null, null, null, null)

        val stats = persistence.getGuildIntroStats(guildId, 2)

        assertEquals(0L, stats.storedBytes)
        assertEquals(0L, stats.totalPlays)
    }

    @Test
    fun `a short row does not throw`() {
        resultRow(1L)

        val stats = persistence.getGuildIntroStats(guildId, 2)

        assertEquals(1L, stats.introCount)
        assertEquals(0L, stats.brokenCount)
    }

    @Test
    fun `the guild and threshold are bound as parameters, not interpolated`() {
        resultRow(0L, 0L, 0L, 0L, 0L, 0L)
        val names = mutableListOf<String>()
        val values = mutableListOf<Any>()
        every { query.setParameter(capture(names), capture(values)) } returns query

        persistence.getGuildIntroStats(guildId, 2)

        assertEquals(listOf("guildId", "threshold"), names)
        assertEquals(listOf<Any>(guildId, 2), values)
    }

    @Test
    fun `the query filters on guild_id rather than scanning every server`() {
        // music_files is keyed by (discord_id, guild_id); the report must use
        // the indexed column, not a LIKE over the composite row id.
        resultRow(0L, 0L, 0L, 0L, 0L, 0L)
        val sql = slot<String>()
        every { em.createNativeQuery(capture(sql)) } returns query

        persistence.getGuildIntroStats(guildId, 2)

        assertTrue(sql.captured.contains("WHERE guild_id = :guildId"), sql.captured)
        assertTrue(sql.captured.contains("octet_length(music_blob)"), sql.captured)
    }

    @Test
    fun `a database failure degrades to an empty report`() {
        // /introstats is a diagnostic; failing it with a stack trace when the
        // database hiccups helps nobody.
        every { query.singleResult } throws IllegalStateException("connection reset")

        val stats = persistence.getGuildIntroStats(guildId, 2)

        assertEquals(0L, stats.introCount)
        assertEquals(0L, stats.storedBytes)
    }
}
