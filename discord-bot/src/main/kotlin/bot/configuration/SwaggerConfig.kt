package bot.configuration

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.EnableWebMvc

@Configuration
@EnableWebMvc
open class SwaggerConfig {

    @Bean
    open fun customOpenApi(): OpenAPI {
        return OpenAPI().info(
            Info().title("Your Bot API")
                .version("1.0")
                .description("API documentation for your Discord bot")
        )
    }
}