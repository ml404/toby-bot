package bot.database.persistence.impl

import bot.database.persistence.IUserPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
open class UserPersistenceImpl : IUserPersistence {
    @PersistenceContext
    lateinit var entityManager: EntityManager


    override fun listGuildUsers(guildId: Long?): List<bot.database.dto.UserDto?> {
        val q: Query = entityManager.createNamedQuery("UserDto.getGuildAll", bot.database.dto.UserDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList as List<bot.database.dto.UserDto?>
    }

    override fun createNewUser(userDto: bot.database.dto.UserDto): bot.database.dto.UserDto {
        val databaseUser = entityManager.find(bot.database.dto.UserDto::class.java, userDto)
        return if ((databaseUser == null)) persistUserDto(userDto) else databaseUser
    }

    override fun getUserById(discordId: Long?, guildId: Long?): bot.database.dto.UserDto? {
        val userQuery: Query = entityManager.createNamedQuery("UserDto.getById", bot.database.dto.UserDto::class.java)
        userQuery.setParameter("discordId", discordId)
        userQuery.setParameter("guildId", guildId)
        return runCatching { userQuery.singleResult as bot.database.dto.UserDto? }.getOrNull()
    }

    override fun updateUser(userDto: bot.database.dto.UserDto): bot.database.dto.UserDto {
        val dbUser = getUserById(userDto.discordId, userDto.guildId)
        if (userDto != dbUser) {
            entityManager.merge(userDto)
            entityManager.flush()
        }

        return userDto
    }

    override fun deleteUser(userDto: bot.database.dto.UserDto) {
        entityManager.remove(userDto.musicDtos)
        entityManager.remove(userDto)
        entityManager.flush()
    }

    override fun deleteUserById(discordId: Long?, guildId: Long?) {
        deleteAssociatedMusicFilesForUserAndGuild(discordId, guildId)

        val userQuery = entityManager.createNamedQuery("UserDto.deleteById")
        userQuery.setParameter("discordId", discordId)
        userQuery.setParameter("guildId", guildId)
        userQuery.executeUpdate()
    }

    private fun deleteAssociatedMusicFilesForUserAndGuild(discordId: Long?, guildId: Long?) {
        val musicFileQuery = entityManager.createNamedQuery("MusicDto.deleteByUser")
        musicFileQuery.setParameter("discordId", discordId)
        musicFileQuery.setParameter("guildId", guildId)
        musicFileQuery.executeUpdate()
    }

    private fun persistUserDto(userDto: bot.database.dto.UserDto): bot.database.dto.UserDto {
        entityManager.persist(userDto)
        entityManager.flush()
        return userDto
    }
}
