package jpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import toby.Application;
import toby.jpa.dto.BrotherDto;
import toby.jpa.persistence.IBrotherPersistence;
import toby.jpa.service.IBrotherService;
import toby.jpa.service.impl.BrotherServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = Application.class)
public class BrotherServiceImplIntegrationTest {

    @Bean
    public IBrotherService brotherService() {
        return new BrotherServiceImpl();
    }

    @Autowired
    private IBrotherService brotherService;

    @Autowired
    private IBrotherPersistence brotherPersistence;


    @BeforeEach
    public void setUp() {
        brotherPersistence.deleteBrotherById(1L);

    }

    @AfterEach
    public void cleanDb(){
        brotherPersistence.deleteBrotherById(1L);
    }

    @Test
    public void whenValidDiscordId_thenBrotherShouldBeFound() {
        BrotherDto brotherDto = new BrotherDto(1L, "a");
        brotherService.createNewBrother(brotherDto);
        BrotherDto dbBrother = brotherService.getBrotherById(brotherDto.getDiscordId());

        assertEquals(dbBrother.getDiscordId(),brotherDto.getDiscordId());
        assertEquals(dbBrother.getBrotherName(),brotherDto.getBrotherName());
    }

    @Test
    public void testUpdate_thenNewBrotherShouldBeReturned() {
        int originalBrotherSize = brotherService.listBrothers().size();
        BrotherDto brotherDto = new BrotherDto(1L, "a");
        brotherService.createNewBrother(brotherDto);
        BrotherDto dbBrother1 = brotherService.getBrotherById(brotherDto.getDiscordId());

        BrotherDto brotherDtoUpdated = new BrotherDto(1L, "b");
        brotherService.updateBrother(brotherDtoUpdated);
        BrotherDto dbBrother2 = brotherService.getBrotherById(brotherDto.getDiscordId());

        int expectedBrotherSize = originalBrotherSize +1;

        assertEquals(dbBrother1.getDiscordId(),brotherDto.getDiscordId());
        assertEquals(dbBrother1.getBrotherName(),brotherDto.getBrotherName());
        assertEquals(dbBrother2.getDiscordId(),brotherDtoUpdated.getDiscordId());
        assertEquals(dbBrother2.getBrotherName(),brotherDtoUpdated.getBrotherName());
        assertEquals(expectedBrotherSize, originalBrotherSize +1);
    }
}
