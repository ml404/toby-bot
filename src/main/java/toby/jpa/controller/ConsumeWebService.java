package toby.jpa.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;
import toby.jpa.dto.MusicDto;

import java.time.Duration;

public class ConsumeWebService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    public static String getWebUrl() {
        return webUrl;
    }

    private static final String webUrl = "https://gibe-toby-bot.herokuapp.com/";

    @Autowired
    WebClient webClient;


    public MusicDto getMusicDto(String id) {

        return webClient.get()
                .uri("/music?id=" + id)
                .retrieve()
                .bodyToMono(MusicDto.class)
                .block(REQUEST_TIMEOUT);
    }
}
