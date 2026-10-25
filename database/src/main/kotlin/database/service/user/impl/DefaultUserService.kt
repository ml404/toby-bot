package database.service.user.impl

import database.dto.user.UserDto
import database.persistence.user.UserPersistence
import database.service.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

@Service
class DefaultUserService : UserService {
    @Autowired
    private lateinit var userService: UserPersistence

    @Cacheable(value = ["users"], key = "'list-' + #a0")
    override fun listGuildUsers(guildId: Long?): List<UserDto?> {
        return userService.listGuildUsers(guildId)
    }

    @CacheEvict(value = ["users"], key = "'list-' + #a0.guildId")
    override fun createNewUser(userDto: UserDto): UserDto {
        return userService.createNewUser(userDto)
    }

    // Deliberately not cached. A user row eagerly carries every intro's audio
    // blob, so a per-user cache entry pins up to ~1.8 MB of heap for 30
    // minutes, and callers re-read the row before writing anyway.
    override fun getUserById(discordId: Long?, guildId: Long?): UserDto? {
        return userService.getUserById(discordId, guildId)
    }

    // Bypass cache deliberately: the trade path needs a fresh DB row + row lock.
    override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? {
        return userService.getUserByIdForUpdate(discordId, guildId)
    }

    @CacheEvict(value = ["users"], key = "'list-' + #a0.guildId")
    override fun updateUser(userDto: UserDto): UserDto {
        return userService.updateUser(userDto)
    }

    @CacheEvict(value = ["users"], key = "'list-' + #a0.guildId")
    override fun deleteUser(userDto: UserDto) {
        userService.deleteUser(userDto)
    }

    @CacheEvict(value = ["users"], key = "'list-' + #a1")
    override fun deleteUserById(discordId: Long?, guildId: Long?) {
        userService.deleteUserById(discordId, guildId)
    }

    @CacheEvict(value = ["users"], allEntries = true)
    override fun clearCache() {
    }
}
