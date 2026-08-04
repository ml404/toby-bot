package database.persistence.music.impl

import common.logging.DiscordLogger
import database.dto.music.MusicDto
import database.persistence.music.GuildIntroStats
import database.persistence.music.MusicFilePersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
class DefaultMusicFilePersistence : MusicFilePersistence {
    @PersistenceContext
    private lateinit var entityManager: EntityManager
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    private fun persistMusicDto(musicDto: MusicDto): MusicDto {
        logger.info { "Persisting $musicDto" }
        entityManager.persist(musicDto)
        entityManager.flush()
        logger.info { "Persisted $musicDto" }
        return musicDto
    }

    @Transactional(readOnly = true)
    override fun isFileAlreadyUploaded(musicDto: MusicDto): MusicDto? {
        return runCatching {
            logger.info { "Checking to see if '${musicDto.musicBlobHash}' has already been uploaded for this guild and user..." }
            val query = entityManager.createQuery(
                "SELECT m FROM MusicDto m WHERE m.musicBlobHash = :musicBlobHash AND m.userDto.discordId = :discordId AND m.userDto.guildId = :guildId",
                MusicDto::class.java
            )
            query.setParameter("musicBlobHash", MusicDto.computeHash(musicDto.musicBlob ?: ByteArray(0)))
            query.setParameter("discordId", musicDto.userDto?.discordId)
            query.setParameter("guildId", musicDto.userDto?.guildId)

            query.resultList.firstOrNull() // Fetch the first matching record, if any
        }.getOrNull()
    }

    override fun createNewMusicFile(musicDto: MusicDto): MusicDto? {
        logger.info { "Creating new music file for ${musicDto.userDto}" }
        if (isFileAlreadyUploaded(musicDto) != null) {
            logger.info { "Duplicate detected, not persisting file" }
            return null
        }
        // A row already at this id means the caller allocated an occupied slot.
        // This used to fall through to updateMusicFile, which merged the new
        // track over the existing one — the user was told their intro saved
        // while the one it landed on was destroyed, with no trace anywhere.
        // Refusing is the only safe answer: callers surface the null, and the
        // worst case becomes "couldn't save" rather than silent data loss.
        entityManager.find(MusicDto::class.java, musicDto.id)?.let {
            logger.error(
                "Refusing to create music file '${musicDto.id}': that slot is already taken. " +
                    "Something allocated an occupied slot — this would have overwritten an existing intro."
            )
            return null
        }
        return persistMusicDto(musicDto)
    }

    override fun getMusicFileById(id: String): MusicDto? {
        return runCatching {
            // Create a native SQL query to retrieve the size of the music_blob column
            val sql = "SELECT LENGTH(music_blob) AS data_size FROM music_files WHERE id = :id"

            val sizeQ = entityManager.createNativeQuery(sql)
            sizeQ.setParameter("id", id)

            // Execute the query

            val result = sizeQ.singleResult

            // Handle the result (assuming it's a Long)
            if (result is Long) {
                logger.info { "Data size in bytes: $result" }
            }

            val q: Query = entityManager.createNamedQuery("MusicDto.getById", MusicDto::class.java)
            q.setParameter("id", id)
            return q.singleResult as MusicDto?
        }.getOrNull()
    }

    override fun updateMusicFile(musicDto: MusicDto): MusicDto? {
        logger.info { "Updating music file for ${musicDto.userDto} " }
        entityManager.merge(musicDto)
        entityManager.flush()
        logger.info { "Updated music file for ${musicDto.userDto}" }
        return musicDto
    }

    override fun deleteMusicFile(musicDto: MusicDto) {
        logger.info { "Deleting music file ${musicDto.userDto} " }
        entityManager.remove(musicDto)
        entityManager.flush()
    }

    override fun deleteMusicFileById(id: String?) {
        logger.info { "Deleting music file ..." }
        val q = entityManager.createNamedQuery("MusicDto.deleteById")
        q.setParameter("id", id)
        q.executeUpdate()
    }

    @Transactional(readOnly = true)
    override fun getGuildIntroStats(guildId: Long, unhealthyThreshold: Int): GuildIntroStats {
        return runCatching {
            // Native because the point is octet_length(music_blob) — the size
            // of the stored audio without reading the audio. There is no JPQL
            // spelling for that, and pulling the rows to measure them in Kotlin
            // would allocate exactly the bytes we're trying to report on.
            val sql = """
                SELECT COUNT(*),
                       COUNT(DISTINCT discord_id),
                       COALESCE(SUM(octet_length(music_blob)), 0),
                       COALESCE(SUM(play_count), 0),
                       COUNT(*) FILTER (WHERE NOT enabled),
                       COUNT(*) FILTER (WHERE failure_count >= :threshold)
                FROM music_files
                WHERE guild_id = :guildId
            """.trimIndent()
            val query = entityManager.createNativeQuery(sql)
            query.setParameter("guildId", guildId)
            query.setParameter("threshold", unhealthyThreshold)

            val row = query.singleResult as Array<*>
            GuildIntroStats(
                introCount = row.longAt(0),
                userCount = row.longAt(1),
                storedBytes = row.longAt(2),
                totalPlays = row.longAt(3),
                disabledCount = row.longAt(4),
                brokenCount = row.longAt(5),
            )
        }.onFailure {
            logger.error("Failed to compute intro stats for guild $guildId: ${it.message}")
        }.getOrDefault(GuildIntroStats())
    }

    // COUNT and SUM come back as Long on Postgres, but the JDBC driver is free
    // to widen SUM to BigInteger/BigDecimal, and an all-NULL SUM would be null
    // were it not for the COALESCE. Normalise rather than cast.
    private fun Array<*>.longAt(index: Int): Long = (getOrNull(index) as? Number)?.toLong() ?: 0L
}
