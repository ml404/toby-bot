package database.service

import database.dto.UserDto

interface UserService {
    fun listGuildUsers(guildId: Long?): List<UserDto?>
    fun createNewUser(userDto: UserDto): UserDto
    fun getUserById(discordId: Long?, guildId: Long?): UserDto?
    fun updateUser(userDto: UserDto): UserDto?
    fun deleteUser(userDto: UserDto)
    fun deleteUserById(discordId: Long?, guildId: Long?)
    fun clearCache()
    fun evictUserFromCache(discordId: Long?, guildId: Long?)
}
