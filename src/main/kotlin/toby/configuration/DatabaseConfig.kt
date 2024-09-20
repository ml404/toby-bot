package toby.configuration

import com.zaxxer.hikari.HikariDataSource
import org.postgresql.Driver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.JpaVendorAdapter
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import java.net.URI
import java.net.URISyntaxException
import java.util.*
import javax.sql.DataSource

@Profile("prod")
@Configuration
open class DatabaseConfig(private val env: Environment) {

    @Bean
    @Throws(URISyntaxException::class)
    open fun dataSource(): DataSource {
        val dbUri = URI(env.getRequiredProperty("DATABASE_URL"))
        val (username, password) = dbUri.userInfo.split(":")
        val dbUrl = "jdbc:postgresql://${dbUri.host}:${dbUri.port}${dbUri.path}?sslmode=require"

        val dataSource = HikariDataSource()
        dataSource.driverClassName = Driver::class.java.name
        dataSource.jdbcUrl = dbUrl
        dataSource.username = username
        dataSource.password = password

        // Additional HikariCP settings can be set here if necessary
        return dataSource
    }

    @Bean
    open fun entityManagerFactory(dataSource: DataSource): LocalContainerEntityManagerFactoryBean {
        val entityManagerFactory = LocalContainerEntityManagerFactoryBean()
        entityManagerFactory.dataSource = dataSource
        entityManagerFactory.setPackagesToScan("toby.jpa.dto")

        val jpaVendorAdapter: JpaVendorAdapter = HibernateJpaVendorAdapter()
        entityManagerFactory.jpaVendorAdapter = jpaVendorAdapter

        val jpaProperties = Properties()
        jpaProperties["hibernate.dialect"] = "org.hibernate.dialect.PostgreSQLDialect"
        entityManagerFactory.setJpaProperties(jpaProperties)

        return entityManagerFactory
    }

    @Bean
    open fun transactionManager(entityManagerFactory: LocalContainerEntityManagerFactoryBean): PlatformTransactionManager {
        val transactionManager = JpaTransactionManager()
        transactionManager.entityManagerFactory = entityManagerFactory.getObject()
        return transactionManager
    }
}
