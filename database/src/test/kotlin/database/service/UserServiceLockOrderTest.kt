package database.service

import database.dto.UserDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure unit test for the `UserService.lockUsersInAscendingOrder` extension.
 *
 * The behaviour we lock down here is the deadlock-avoidance contract:
 * locks always happen in ascending discord-id order regardless of the
 * caller's input order, duplicates collapse to a single lock, and
 * missing rows surface as `null` values without aborting the rest.
 */
class UserServiceLockOrderTest {

    private val guildId = 42L

    @Test
    fun `locks in ascending order regardless of input order`() {
        val recording = RecordingUserService()
        recording.seed(7L); recording.seed(3L); recording.seed(11L)

        recording.lockUsersInAscendingOrder(listOf(11L, 3L, 7L), guildId)

        assertEquals(listOf(3L, 7L, 11L), recording.lockOrder)
    }

    @Test
    fun `dedups before locking`() {
        val recording = RecordingUserService()
        recording.seed(5L); recording.seed(2L)

        val map = recording.lockUsersInAscendingOrder(listOf(5L, 2L, 5L, 2L, 5L), guildId)

        assertEquals(listOf(2L, 5L), recording.lockOrder, "duplicate ids must lock once")
        assertEquals(setOf(2L, 5L), map.keys, "result keyed by discord id, dedup'd")
    }

    @Test
    fun `missing rows surface as null without aborting`() {
        val recording = RecordingUserService()
        recording.seed(2L)
        // 5 is not seeded.

        val map = recording.lockUsersInAscendingOrder(listOf(2L, 5L), guildId)

        assertEquals(listOf(2L, 5L), recording.lockOrder, "still attempts the lock for the missing id")
        assertEquals(2L, map[2L]?.discordId)
        assertNull(map[5L])
    }

    @Test
    fun `empty input does no work`() {
        val recording = RecordingUserService()
        val map = recording.lockUsersInAscendingOrder(emptyList(), guildId)
        assertEquals(emptyList<Long>(), recording.lockOrder)
        assertEquals(emptyMap<Long, UserDto?>(), map)
    }

    @Test
    fun `result map preserves ascending iteration order`() {
        val recording = RecordingUserService()
        listOf(10L, 1L, 5L).forEach(recording::seed)

        val map = recording.lockUsersInAscendingOrder(listOf(10L, 1L, 5L), guildId)

        assertEquals(listOf(1L, 5L, 10L), map.keys.toList())
    }

    @Test
    fun `passing a Set still locks in ascending order`() {
        val recording = RecordingUserService()
        listOf(9L, 4L, 6L).forEach(recording::seed)

        recording.lockUsersInAscendingOrder(setOf(9L, 4L, 6L), guildId)

        assertEquals(listOf(4L, 6L, 9L), recording.lockOrder)
    }

    private class RecordingUserService : UserService {
        private val users = mutableMapOf<Pair<Long, Long>, UserDto>()
        val lockOrder: MutableList<Long> = mutableListOf()

        fun seed(discordId: Long, guildId: Long = 42L) {
            users[discordId to guildId] = UserDto(discordId, guildId)
        }

        override fun listGuildUsers(guildId: Long?): List<UserDto?> =
            users.values.filter { it.guildId == guildId }
        override fun createNewUser(userDto: UserDto): UserDto {
            users[userDto.discordId to userDto.guildId] = userDto
            return userDto
        }
        override fun getUserById(discordId: Long?, guildId: Long?): UserDto? =
            users[discordId!! to guildId!!]
        override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? {
            lockOrder.add(discordId!!)
            return users[discordId to guildId!!]
        }
        override fun updateUser(userDto: UserDto): UserDto {
            users[userDto.discordId to userDto.guildId] = userDto
            return userDto
        }
        override fun deleteUser(userDto: UserDto) { users.remove(userDto.discordId to userDto.guildId) }
        override fun deleteUserById(discordId: Long?, guildId: Long?) {
            users.remove(discordId!! to guildId!!)
        }
        override fun clearCache() {}
        override fun evictUserFromCache(discordId: Long?, guildId: Long?) {}
    }
}
