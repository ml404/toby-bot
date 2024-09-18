package configuration

import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import toby.jpa.service.*

@TestConfiguration
open class TestDatabaseConfig {

    @Bean
    open fun userService(): IUserService = mockk(relaxed = true)

    @Bean
    open fun musicFileService(): IMusicFileService = mockk(relaxed = true)

    @Bean
    open fun configService(): IConfigService = mockk(relaxed = true)

    @Bean
    open fun brotherService(): IBrotherService = mockk(relaxed = true)

    @Bean
    open fun excuseService(): IExcuseService = mockk(relaxed = true)
}