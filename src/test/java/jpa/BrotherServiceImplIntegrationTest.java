package jpa;

import org.apache.commons.collections4.IterableUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import toby.Application;
import toby.jpa.dto.BrotherDto;
import toby.jpa.service.IBrotherService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = Application.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
public class BrotherServiceImplIntegrationTest {

    @Autowired
    private IBrotherService brotherService;

    @BeforeEach
    public void setUp() {
        brotherService.deleteBrotherById(6L);
    }

    @AfterEach
    public void cleanDb() {
        brotherService.deleteBrotherById(6L);
    }

    @Test
    public void whenValidDiscordId_thenBrotherShouldBeFound() {
        BrotherDto brotherDto = new BrotherDto(6L, "a");
        brotherService.createNewBrother(brotherDto);
        Optional<BrotherDto> dbBrotherOptional = brotherService.getBrotherById(brotherDto.getDiscordId());

        assertTrue(dbBrotherOptional.isPresent());
        BrotherDto dbBrother = dbBrotherOptional.get();
        assertEquals(dbBrother.getDiscordId(), brotherDto.getDiscordId());
        assertEquals(dbBrother.getBrotherName(), brotherDto.getBrotherName());
    }

    @Test
    public void testUpdate_thenNewBrotherShouldBeReturned() {
        int originalBrotherSize = IterableUtils.toList(brotherService.listBrothers()).size();
        BrotherDto brotherDto = new BrotherDto(6L, "a");
        brotherService.createNewBrother(brotherDto);
        Optional<BrotherDto> brotherDtoOptional1 = brotherService.getBrotherById(brotherDto.getDiscordId());

        BrotherDto brotherDtoUpdated = new BrotherDto(6L, "b");
        brotherService.updateBrother(brotherDtoUpdated);
        Optional<BrotherDto> brotherDtoOptional2 = brotherService.getBrotherById(brotherDto.getDiscordId());

        int expectedBrotherSize = originalBrotherSize + 1;


        assertTrue(brotherDtoOptional1.isPresent());
        assertTrue(brotherDtoOptional2.isPresent());

        BrotherDto dbBrother1 = brotherDtoOptional1.get();
        BrotherDto dbBrother2 = brotherDtoOptional2.get();
        assertEquals(dbBrother1.getDiscordId(), brotherDto.getDiscordId());
        assertEquals(dbBrother1.getBrotherName(), brotherDto.getBrotherName());
        assertEquals(dbBrother2.getDiscordId(), brotherDtoUpdated.getDiscordId());
        assertEquals(dbBrother2.getBrotherName(), brotherDtoUpdated.getBrotherName());
        assertEquals(expectedBrotherSize, originalBrotherSize + 1);
    }
    @Test
    public void testDataSQL() {
        assertEquals(5, IterableUtils.toList(brotherService.listBrothers()).size());
    }
}
