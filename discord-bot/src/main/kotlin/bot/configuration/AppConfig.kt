package bot.configuration

import bot.toby.handler.EventWaiter
import bot.toby.helpers.HttpHelper
import common.configuration.YoutubeProxySettings
import common.logging.DiscordLogger
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Credentials
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.net.InetSocketAddress
import java.net.Proxy

private val logger: DiscordLogger = DiscordLogger.createLogger(AppConfig::class.java)

@Profile("prod")
@Configuration
class AppConfig {

    @Bean
    fun httpClient(): HttpClient {
        val proxy = YoutubeProxySettings.fromEnv()
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            if (proxy != null) {
                engine {
                    config {
                        proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
                        if (proxy.hasAuth) {
                            proxyAuthenticator(Authenticator { _, response ->
                                val credential = Credentials.basic(proxy.user!!, proxy.pass!!)
                                response.request.newBuilder()
                                    .header("Proxy-Authorization", credential)
                                    .build()
                            })
                        }
                    }
                }
                logger.info { "Ktor HTTP proxy enabled: ${proxy.host}:${proxy.port} (auth=${proxy.hasAuth})" }
            }
        }
    }

    @Bean
    fun httpHelper(client: HttpClient): HttpHelper {
        return HttpHelper(client)
    }

    @Bean
    fun eventWaiter(): EventWaiter {
        return EventWaiter()
    }
}
