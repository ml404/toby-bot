package toby.jpa.persistence.impl

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Query
import mu.KotlinLogging
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import toby.helpers.FileUtils.computeHash
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.persistence.IMusicFilePersistence

@Repository
@Transactional
open class MusicFilePersistenceImpl : IMusicFilePersistence {
    @PersistenceContext
    lateinit var entityManager: EntityManager
    private val logger = KotlinLogging.logger {}

    private fun persistMusicDto(musicDto: MusicDto): MusicDto {
        entityManager.persist(musicDto)
        entityManager.flush()
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
            query.setParameter("musicBlobHash", computeHash(musicDto.musicBlob ?: ByteArray(0)))
            query.setParameter("discordId", musicDto.userDto?.discordId)
            query.setParameter("guildId", musicDto.userDto?.guildId)

            query.resultList.firstOrNull() // Fetch the first matching record, if any
        }.getOrNull()
    }

    override fun createNewMusicFile(musicDto: MusicDto): MusicDto? {
        logger.info { "Creating new music file for ${musicDto.userDto}" }
        createUserForMusicFile(musicDto.userDto!!)
        if (isFileAlreadyUploaded(musicDto) == null) {
            logger.info { "Duplicate detected, not persisting file" }
            return null
        }
        val databaseMusicFile = entityManager.find(MusicDto::class.java, musicDto.id)
        return if (databaseMusicFile == null) persistMusicDto(musicDto) else updateMusicFile(musicDto)
    }

    private fun createUserForMusicFile(userDto: UserDto) {
        runCatching {
            entityManager
                .createNamedQuery("UserDto.getById", UserDto::class.java)
                .setParameter("discordId", userDto.discordId)
                .setParameter("guildId", userDto.guildId)
                .singleResult
        }.getOrElse { persistUserDto(userDto) }
    }

    private fun persistUserDto(userDto: UserDto) {
        entityManager.persist(userDto)
        entityManager.flush()
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
                println("Data size in bytes: $result")
            }

            val q: Query = entityManager.createNamedQuery("MusicDto.getById", MusicDto::class.java)
            q.setParameter("id", id)
            return q.singleResult as MusicDto?
        }.getOrNull()
    }

    override fun updateMusicFile(musicDto: MusicDto): MusicDto? {
        logger.info { "Updating music file for ${musicDto.userDto}" }
        if (isFileAlreadyUploaded(musicDto) == null) {
            logger.info { "Duplicate detected, not persisting file" }
            return null
        }
        createUserForMusicFile(musicDto.userDto!!)
        entityManager.merge(musicDto)
        entityManager.flush()
        return musicDto
    }

    override fun deleteMusicFile(musicDto: MusicDto) {
        entityManager.remove(musicDto)
        entityManager.flush()
    }

    override fun deleteMusicFileById(id: String?) {
        val q = entityManager.createNamedQuery("MusicDto.deleteById")
        q.setParameter("id", id)
        q.executeUpdate()
    }
}
