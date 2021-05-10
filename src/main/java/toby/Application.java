package toby;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.web.reactive.function.client.WebClient;


@SpringBootApplication
@ComponentScan("toby")
public class Application {


    public static void main(String[] args) {
        new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.REACTIVE)
                .run(args);
    }

    @Bean
    public static WebClient localApiClient() {
        return WebClient.create("http://localhost:8080/api/v1");
    }
}


