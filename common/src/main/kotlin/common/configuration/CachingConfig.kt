package common.configuration

import common.helpers.Cache
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.cache.support.SimpleCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Profile("prod")
@Configuration
@EnableCaching
class CachingConfig {
    @Bean
    fun cacheManager(): CacheManager {
        val cacheManager = SimpleCacheManager()
        cacheManager.setCaches(
            listOf(
                ConcurrentMapCache("configs"),
                ConcurrentMapCache("brothers"),
                ConcurrentMapCache("users"),
                ConcurrentMapCache("music"),
                ConcurrentMapCache("excuses")
            )
        )
        return cacheManager
    }

    @Bean
    fun cache(): Cache {
        return Cache(86400, 3600, 2)
    }
}
