package toby.jpa.persistence.impl

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import toby.jpa.dto.UserDto
import toby.jpa.persistence.IUserPersistence
import toby.jpa.service.IMusicFileService

@Repository
@Transactional
open class UserPersistenceImpl internal constructor(private val musicFileService: IMusicFileService) : IUserPersistence {
    @PersistenceContext
    lateinit var entityManager: EntityManager


    override fun listGuildUsers(guildId: Long?): List<UserDto?> {
        val q: Query = entityManager.createNamedQuery("UserDto.getGuildAll", UserDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList as List<UserDto?>
    }

    override fun createNewUser(userDto: UserDto): UserDto {
        createMusicFileEntry(userDto)
        val databaseUser = entityManager.find(UserDto::class.java, userDto)
        val result = if ((databaseUser == null)) persistConfigDto(userDto) else databaseUser

        return result
    }

    private fun createMusicFileEntry(userDto: UserDto) {
        musicFileService.createNewMusicFile(userDto.musicDto)
        entityManager.flush()
    }

    override fun getUserById(discordId: Long?, guildId: Long?): UserDto? {
        val userQuery: Query = entityManager.createNamedQuery("UserDto.getById", UserDto::class.java)
        userQuery.setParameter("discordId", discordId)
        userQuery.setParameter("guildId", guildId)
        return runCatching { userQuery.singleResult as UserDto }.getOrNull()
    }

    override fun updateUser(userDto: UserDto): UserDto {
        val dbUser = getUserById(userDto.discordId, userDto.guildId)
        val musicFileById = musicFileService.getMusicFileById(userDto.musicDto?.id)
        val requestMusicDto = dbUser?.musicDto
        if (requestMusicDto != musicFileById) {
            musicFileService.updateMusicFile(requestMusicDto)
        }

        if (userDto != dbUser) {
            entityManager.merge(userDto)
            entityManager.flush()
        }

        return userDto
    }

    override fun deleteUser(userDto: UserDto) {
        entityManager.remove(userDto.musicDto)
        entityManager.remove(userDto)
        entityManager.flush()
    }

    override fun deleteUserById(discordId: Long?, guildId: Long?) {
        val userQuery = entityManager.createNamedQuery("UserDto.deleteById")
        userQuery.setParameter("discordId", discordId)
        userQuery.setParameter("guildId", guildId)
        userQuery.executeUpdate()
    }

    private fun persistConfigDto(userDto: UserDto): UserDto {
        entityManager.persist(userDto)
        entityManager.flush()
        return userDto
    }
}
