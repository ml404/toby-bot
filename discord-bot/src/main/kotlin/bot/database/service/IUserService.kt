package bot.database.service

interface IUserService {
    fun listGuildUsers(guildId: Long?): List<bot.database.dto.UserDto?>
    fun createNewUser(userDto: bot.database.dto.UserDto): bot.database.dto.UserDto
    fun getUserById(discordId: Long?, guildId: Long?): bot.database.dto.UserDto?
    fun updateUser(userDto: bot.database.dto.UserDto): bot.database.dto.UserDto?
    fun deleteUser(userDto: bot.database.dto.UserDto)
    fun deleteUserById(discordId: Long?, guildId: Long?)
    fun clearCache()
    fun evictUserFromCache(discordId: Long?, guildId: Long?)
}
