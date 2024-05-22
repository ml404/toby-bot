package toby.jpa.configuration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Arrays;

@Configuration
@EnableCaching
@Profile("test")
public class TestCachingConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(Arrays.asList(
                new ConcurrentMapCache("configs"),
                new ConcurrentMapCache("brothers"),
                new ConcurrentMapCache("users"),
                new ConcurrentMapCache("music"),
                new ConcurrentMapCache("excuses")
                ));
        return cacheManager;
    }
}
