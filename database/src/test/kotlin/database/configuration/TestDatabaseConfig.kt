package database.configuration

import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Profile
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Boots a real PostgreSQL container for integration tests so Flyway migrations
 * apply against the same engine production runs. `@ServiceConnection` wires
 * the container's JDBC URL/credentials into Spring Boot's auto-configured
 * DataSource — no manual datasource bean, no schema.sql, no H2 PG-mode
 * approximation gap.
 *
 * `@EntityScan("database.dto")` mirrors the explicit
 * `setPackagesToScan("database.dto")` in the prod `DatabaseConfig`. Without
 * it, Spring Boot's auto-configured EntityManagerFactory only scans the
 * package of `@SpringBootApplication` (i.e. `app`), so DTOs in
 * `database.dto` and their `@NamedQuery`s would silently fail to register.
 *
 * Container lifecycle: started lazily on first context-load, reused across
 * test classes (testcontainers-jvm reuses containers tagged with the same
 * image when reuse is enabled), destroyed on JVM exit.
 */
@Profile("test")
@ComponentScan(basePackages = ["database"])
@EntityScan("database.dto")
@TestConfiguration(proxyBeanMethods = false)
open class TestDatabaseConfig {

    @Bean
    @ServiceConnection
    open fun postgresContainer(): PostgreSQLContainer<*> {
        return PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
    }
}
