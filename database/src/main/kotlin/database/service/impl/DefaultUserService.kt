package database.service.impl

import database.dto.UserDto
import database.persistence.UserPersistence
import database.service.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service

@Service
class DefaultUserService : UserService {
    @Autowired
    private lateinit var userService: UserPersistence

    @Cacheable(value = ["users"], key = "'list-' + #a0")
    override fun listGuildUsers(guildId: Long?): List<UserDto?> {
        return userService.listGuildUsers(guildId)
    }

    @Caching(
        put = [CachePut(value = ["users"], key = "#a0.discordId + #a0.guildId")],
        evict = [CacheEvict(value = ["users"], key = "'list-' + #a0.guildId")]
    )
    override fun createNewUser(userDto: UserDto): UserDto {
        return userService.createNewUser(userDto)
    }

    @CachePut(value = ["users"], key = "#a0 + #a1")
    override fun getUserById(discordId: Long?, guildId: Long?): UserDto? {
        return userService.getUserById(discordId, guildId)
    }

    // Bypass cache deliberately: the trade path needs a fresh DB row + row lock.
    override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? {
        return userService.getUserByIdForUpdate(discordId, guildId)
    }

    @Caching(
        put = [CachePut(value = ["users"], key = "#a0.discordId + #a0.guildId")],
        evict = [CacheEvict(value = ["users"], key = "'list-' + #a0.guildId")]
    )
    override fun updateUser(userDto: UserDto): UserDto {
        return userService.updateUser(userDto)
    }

    @Caching(evict = [
        CacheEvict(value = ["users"], key = "#a0.discordId + #a0.guildId"),
        CacheEvict(value = ["users"], key = "'list-' + #a0.guildId")
    ])
    override fun deleteUser(userDto: UserDto) {
        userService.deleteUser(userDto)
    }

    @Caching(evict = [
        CacheEvict(value = ["users"], key = "#a0 + #a1"),
        CacheEvict(value = ["users"], key = "'list-' + #a1")
    ])
    override fun deleteUserById(discordId: Long?, guildId: Long?) {
        userService.deleteUserById(discordId, guildId)
    }

    @CacheEvict(value = ["users"], allEntries = true)
    override fun clearCache() {
    }

    @CacheEvict(value = ["users"], key = "#a0 + #a1")
    override fun evictUserFromCache(discordId: Long?, guildId: Long?) {
    }
}
