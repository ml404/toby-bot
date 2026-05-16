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
            // Belt for local/dev runs; the authoritative setter is the
            // `-Djdk.http.auth.tunneling.disabledSchemes=` flag in Procfile
            // so the property is present before any `-javaagent` premain
            // (e.g. heroku-java-metrics-agent) can touch HttpURLConnection
            // and freeze its static disabled-schemes set with `Basic` in it.
            if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) {
                System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
            }
            SpringApplication.run(Application::class.java, *args)
        }
    }
}
