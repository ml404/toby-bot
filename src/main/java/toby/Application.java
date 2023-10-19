package toby;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ContextConfiguration;
import toby.jpa.configuration.CachingConfig;
import toby.jpa.configuration.DatabaseConfig;


@SpringBootApplication
@ComponentScan("toby")
@ContextConfiguration(classes = {DatabaseConfig.class, CachingConfig.class, Application.class})
@EnableCaching
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}


