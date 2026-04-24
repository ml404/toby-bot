package database.service.impl

import database.dto.UserDto
import database.persistence.UserPersistence
import database.service.UserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.cache.support.SimpleCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the user cache. A write through any of the mutating methods on
 * [DefaultUserService] must evict the cached `listGuildUsers` result for that guild —
 * otherwise `ModerationWebService.getGuildOverview` serves a stale list after
 * `adjustSocialCredit`, exactly the "applying social credit doesn't update on refresh" bug.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [DefaultUserServiceCacheTest.TestContext::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DefaultUserServiceCacheTest {

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var persistence: CountingUserPersistence

    @BeforeEach
    fun reset() {
        persistence.reset()
        userService.clearCache()
    }

    @Test
    fun `listGuildUsers is cached between reads for the same guild`() {
        userService.listGuildUsers(GUILD_ID)
        userService.listGuildUsers(GUILD_ID)
        userService.listGuildUsers(GUILD_ID)

        assertEquals(1, persistence.listCalls.get(), "second & third read should be served from cache")
    }

    @Test
    fun `updateUser evicts the guild-user list cache`() {
        userService.listGuildUsers(GUILD_ID)
        assertEquals(1, persistence.listCalls.get())

        userService.updateUser(UserDto(discordId = USER_ID, guildId = GUILD_ID))

        userService.listGuildUsers(GUILD_ID)
        assertEquals(2, persistence.listCalls.get(), "list cache must be evicted on updateUser")
    }

    @Test
    fun `createNewUser evicts the guild-user list cache`() {
        userService.listGuildUsers(GUILD_ID)
        assertEquals(1, persistence.listCalls.get())

        userService.createNewUser(UserDto(discordId = 99L, guildId = GUILD_ID))

        userService.listGuildUsers(GUILD_ID)
        assertEquals(2, persistence.listCalls.get(), "list cache must be evicted on createNewUser")
    }

    @Test
    fun `deleteUser evicts the guild-user list cache`() {
        userService.listGuildUsers(GUILD_ID)
        assertEquals(1, persistence.listCalls.get())

        userService.deleteUser(UserDto(discordId = USER_ID, guildId = GUILD_ID))

        userService.listGuildUsers(GUILD_ID)
        assertEquals(2, persistence.listCalls.get(), "list cache must be evicted on deleteUser")
    }

    @Test
    fun `deleteUserById evicts the guild-user list cache`() {
        userService.listGuildUsers(GUILD_ID)
        assertEquals(1, persistence.listCalls.get())

        userService.deleteUserById(USER_ID, GUILD_ID)

        userService.listGuildUsers(GUILD_ID)
        assertEquals(2, persistence.listCalls.get(), "list cache must be evicted on deleteUserById")
    }

    @Test
    fun `list cache for one guild is not evicted when a different guild is mutated`() {
        userService.listGuildUsers(GUILD_ID)
        userService.listGuildUsers(OTHER_GUILD_ID)
        assertEquals(2, persistence.listCalls.get())

        userService.updateUser(UserDto(discordId = USER_ID, guildId = OTHER_GUILD_ID))

        userService.listGuildUsers(GUILD_ID)
        assertEquals(2, persistence.listCalls.get(), "GUILD_ID list should still be cached")

        userService.listGuildUsers(OTHER_GUILD_ID)
        assertEquals(3, persistence.listCalls.get(), "OTHER_GUILD_ID list should be evicted")
    }

    companion object {
        private const val GUILD_ID = 42L
        private const val OTHER_GUILD_ID = 43L
        private const val USER_ID = 1L
    }

    @Configuration
    @EnableCaching
    @Import(DefaultUserService::class)
    open class TestContext {

        @Bean
        open fun userPersistence(): CountingUserPersistence = CountingUserPersistence()

        @Bean
        open fun cacheManager(): CacheManager = SimpleCacheManager().apply {
            setCaches(listOf(ConcurrentMapCache("users")))
            initializeCaches()
        }
    }

    class CountingUserPersistence : UserPersistence {
        val listCalls = AtomicInteger(0)

        fun reset() {
            listCalls.set(0)
        }

        override fun listGuildUsers(guildId: Long?): List<UserDto?> {
            listCalls.incrementAndGet()
            return emptyList()
        }

        override fun createNewUser(userDto: UserDto): UserDto = userDto
        override fun getUserById(discordId: Long?, guildId: Long?): UserDto? = null
        override fun getUserByIdForUpdate(discordId: Long?, guildId: Long?): UserDto? = null
        override fun updateUser(userDto: UserDto): UserDto = userDto
        override fun deleteUser(userDto: UserDto) {}
        override fun deleteUserById(discordId: Long?, guildId: Long?) {}
    }
}
