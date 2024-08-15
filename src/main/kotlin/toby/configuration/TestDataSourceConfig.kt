package toby.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

@Profile("test")
@Configuration
open class TestDataSourceConfig {
    @Bean
    open fun dataSource(): EmbeddedDatabase {
        return EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2) // Use H2 in-memory database
            .build()
    }
}

