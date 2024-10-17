package database.service

interface IUserService {
    fun listGuildUsers(guildId: Long?): List<database.dto.UserDto?>
    fun createNewUser(userDto: database.dto.UserDto): database.dto.UserDto
    fun getUserById(discordId: Long?, guildId: Long?): database.dto.UserDto?
    fun updateUser(userDto: database.dto.UserDto): database.dto.UserDto?
    fun deleteUser(userDto: database.dto.UserDto)
    fun deleteUserById(discordId: Long?, guildId: Long?)
    fun clearCache()
    fun evictUserFromCache(discordId: Long?, guildId: Long?)
}
