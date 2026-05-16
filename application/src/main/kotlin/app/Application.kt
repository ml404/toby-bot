package app

import database.configuration.FlywayGuardConfig
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["app", "bot", "common", "core", "database", "web"])
@EnableCaching
@EnableScheduling
@Import(FlywayGuardConfig::class)
class Application {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // sun.net.www.protocol.http.HttpURLConnection caches the
            // tunnelling disabled-schemes list in a static initializer that
            // fires on the first HTTP/HTTPS open. The default list contains
            // `Basic`, which makes the JDK refuse to answer a 407 on an
            // HTTPS CONNECT — i.e. our authenticated YouTube proxy. Clear
            // it before Spring kicks off and triggers any outbound JDK HTTP.
            if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
                System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
            }
            SpringApplication.run(Application::class.java, *args)
        }
    }
}
