package web

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.ComponentScan
import kotlin.jvm.java

@SpringBootApplication
@ComponentScan
@EnableCaching
open class WebApplication {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(WebApplication::class.java, *args)
        }
    }
}