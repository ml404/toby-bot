package toby.jpa.persistence.impl

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.persistence.IMusicFilePersistence

@Repository
@Transactional
open class MusicFilePersistenceImpl : IMusicFilePersistence {
    @PersistenceContext
    lateinit var entityManager: EntityManager


    private fun persistMusicDto(musicDto: MusicDto): MusicDto {
        entityManager.persist(musicDto)
        entityManager.flush()
        return musicDto
    }

    override fun createNewMusicFile(musicDto: MusicDto): MusicDto {
        createUserForMusicFile(musicDto.userDto!!)
        val databaseMusicFile = entityManager.find(MusicDto::class.java, musicDto.id)
        return if ((databaseMusicFile == null)) persistMusicDto(musicDto) else updateMusicFile(musicDto)
    }

    private fun createUserForMusicFile(userDto: UserDto) {
        val databaseUser = entityManager.find(UserDto::class.java, userDto)
        if ((databaseUser == null)) persistUserDto(userDto)
    }

    private fun persistUserDto(userDto: UserDto): UserDto {
        entityManager.persist(userDto)
        entityManager.flush()
        return userDto
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

    override fun updateMusicFile(musicDto: MusicDto): MusicDto {
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
