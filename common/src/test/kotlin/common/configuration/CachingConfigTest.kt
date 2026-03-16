package common.configuration

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.cache.caffeine.CaffeineCacheManager

class CachingConfigTest {

    private val config = CachingConfig()

    @Test
    fun `cacheManager returns a CaffeineCacheManager`() {
        val manager = config.cacheManager()
        assert(manager is CaffeineCacheManager) {
            "Expected CaffeineCacheManager but got ${manager::class.simpleName}"
        }
    }

    @Test
    fun `cacheManager exposes all expected named caches`() {
        val manager = config.cacheManager()
        listOf("configs", "brothers", "users", "music", "excuses").forEach { name ->
            assertNotNull(manager.getCache(name)) { "Cache '$name' should be present" }
        }
    }
}
