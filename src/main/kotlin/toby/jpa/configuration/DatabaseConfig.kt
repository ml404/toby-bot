package toby.jpa.configuration

import org.postgresql.Driver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.JpaVendorAdapter
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import java.net.URI
import java.net.URISyntaxException
import java.util.*
import javax.sql.DataSource

@Configuration
@Profile("prod")
open class DatabaseConfig {
    @Bean
    @Throws(URISyntaxException::class)
    open fun dataSource(): DataSource {
        val dbUri = URI(System.getenv("DATABASE_URL"))
        val username = dbUri.userInfo.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[0]
        val password = dbUri.userInfo.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1]
        val dbUrl = "jdbc:postgresql://" + dbUri.host + ':' + dbUri.port + dbUri.path + "?sslmode=require"
        val dataSource = SimpleDriverDataSource()
        dataSource.driver = Driver()
        dataSource.url = dbUrl
        dataSource.username = username
        dataSource.password = password
        return dataSource
    }

    @Bean
    open fun entityManagerFactory(dataSource: DataSource?): LocalContainerEntityManagerFactoryBean {
        val entityManagerFactory = LocalContainerEntityManagerFactoryBean()
        if (dataSource != null) {
            entityManagerFactory.dataSource = dataSource
        }
        entityManagerFactory.setPackagesToScan("toby.jpa.dto")

        val jpaVendorAdapter: JpaVendorAdapter = HibernateJpaVendorAdapter()
        entityManagerFactory.jpaVendorAdapter = jpaVendorAdapter

        val jpaProperties = Properties()
        // Configure your JPA properties as needed
        jpaProperties["hibernate.dialect"] = "org.hibernate.dialect.PostgreSQLDialect"
        // Add other properties as needed
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