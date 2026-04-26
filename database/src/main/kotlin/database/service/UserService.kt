package database.service

import database.dto.UserDto

interface UserService {
    fun listGuildUsers(guildId: Long?): List<UserDto?>
    fun createNewUser(userDto: UserDto): UserDto
    fun getUserById(discordId: Long?, guildId: Long?): UserDto?

    // Non-cached pessimistic-lock read — must run inside @Transactional.
    fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto?

    fun updateUser(userDto: UserDto): UserDto?
    fun deleteUser(userDto: UserDto)
    fun deleteUserById(discordId: Long?, guildId: Long?)
    fun clearCache()
    fun evictUserFromCache(discordId: Long?, guildId: Long?)
}

/**
 * Lock every user row in [ids] for the given [guildId] in deterministic
 * ascending discord-id order. This is the standard deadlock-avoidance
 * pattern any time a transaction needs to take pessimistic locks on
 * multiple users at once: if every caller acquires in the same order,
 * two concurrent transactions can never form a cycle.
 *
 * Duplicates in [ids] are de-duped before locking. The returned map is
 * keyed by discord id; missing rows surface as `null` values so callers
 * can decide whether the absence is fatal (e.g. `DuelService` returns
 * `UnknownInitiator`/`UnknownOpponent`) or expected (e.g. `PokerService`
 * eviction skips already-vanished users).
 *
 * Must run inside a `@Transactional` boundary — that's where the FOR
 * UPDATE locks live.
 */
fun UserService.lockUsersInAscendingOrder(
    ids: Collection<Long>,
    guildId: Long
): Map<Long, UserDto?> {
    val sorted = ids.toSet().sorted()
    val result = LinkedHashMap<Long, UserDto?>(sorted.size)
    for (id in sorted) {
        result[id] = getUserByIdForUpdate(id, guildId)
    }
    return result
}
