package database.persistence

import database.dto.UserDto

interface UserPersistence {
    fun listGuildUsers(guildId: Long?): List<UserDto?>
    fun createNewUser(userDto: UserDto): UserDto
    fun getUserById(discordId: Long?, guildId: Long?): UserDto?

    // SELECT ... FOR UPDATE. Only call inside an active @Transactional — the
    // row lock is released when the enclosing transaction commits/rolls back.
    fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto?

    fun updateUser(userDto: UserDto): UserDto
    fun deleteUser(userDto: UserDto)
    fun deleteUserById(discordId: Long?, guildId: Long?)
}
