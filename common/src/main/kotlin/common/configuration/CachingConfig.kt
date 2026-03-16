package common.configuration

import com.github.benmanes.caffeine.cache.Caffeine
import common.helpers.Cache
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.util.concurrent.TimeUnit

@Profile("prod")
@Configuration
@EnableCaching
class CachingConfig {
    @Bean
    fun cacheManager(): CacheManager {
        val manager = CaffeineCacheManager("configs", "brothers", "users", "music", "excuses")
        manager.setCaffeine(
            Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(30, TimeUnit.MINUTES)
        )
        return manager
    }

    @Bean
    fun cache(): Cache {
        return Cache(86400, 3600, 2)
    }
}
