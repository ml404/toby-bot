package bot.database.service.impl

import bot.database.persistence.IUserPersistence
import bot.database.service.IUserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

@Service
open class UserServiceImpl : IUserService {
    @Autowired
    lateinit var userService: IUserPersistence

    @Cacheable(value = ["users"])
    override fun listGuildUsers(guildId: Long?): List<bot.database.dto.UserDto?> {
        return userService.listGuildUsers(guildId)
    }

    @CachePut(value = ["users"], key = "#userDto.discordId+#userDto.guildId")
    override fun createNewUser(userDto: bot.database.dto.UserDto): bot.database.dto.UserDto {
        return userService.createNewUser(userDto)
    }

    @CachePut(value = ["users"], key = "#discordId+#guildId")
    override fun getUserById(discordId: Long?, guildId: Long?): bot.database.dto.UserDto? {
        return userService.getUserById(discordId, guildId)
    }

    @CachePut(value = ["users"], key = "#userDto.discordId+#userDto.guildId")
    override fun updateUser(userDto: bot.database.dto.UserDto): bot.database.dto.UserDto {
        return userService.updateUser(userDto)
    }

    @CacheEvict(value = ["users"], key = "#userDto.discordId+#user.guildId")
    override fun deleteUser(userDto: bot.database.dto.UserDto) {
        userService.deleteUser(userDto)
    }

    @CacheEvict(value = ["users"], key = "#discordId+#guildId")
    override fun deleteUserById(discordId: Long?, guildId: Long?) {
        userService.deleteUserById(discordId, guildId)
    }

    @CacheEvict(value = ["users"], allEntries = true)
    override fun clearCache() {
    }

    @CacheEvict(value = ["users"], key = "#discordId+#guildId")
    override fun evictUserFromCache(discordId: Long?, guildId: Long?) {
    }
}
