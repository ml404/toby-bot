package jpa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import toby.Application;
import toby.jpa.dto.ConfigDto;
import toby.jpa.service.IConfigService;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = Application.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
public class ConfigServiceImplIntegrationTest {

    @Autowired
    private IConfigService configService;


    @BeforeEach
    public void setUp() {
        configService.clearCache();
    }

    @AfterEach
    public void cleanUp(){
    }
    @Test
    public void testDataSQL() {
        assertEquals(3, configService.listAllConfig().size());
    }

    @Test
    public void whenValidNameAndGuild_thenConfigShouldBeFound() {
        ConfigDto configDto = new ConfigDto("TOKEN", "1234", "test");
        configService.createNewConfig(configDto);
        ConfigDto dbConfig = configService.getConfigByName(configDto.getName(), configDto.getGuildId());

        assertEquals(dbConfig.getName(),configDto.getName());
        assertEquals(dbConfig.getGuildId(),configDto.getGuildId());
        configService.deleteAll("test");
    }

    @Test
    public void testUpdate_thenNewConfigShouldBeReturned() {
        ConfigDto configDto = new ConfigDto("TOKEN", "1234", "test");
        configService.createNewConfig(configDto);
        ConfigDto dbConfig1 = configService.getConfigByName(configDto.getName(), configDto.getGuildId());

        ConfigDto configDtoUpdated = new ConfigDto("TOKEN", "12345", "test");
        configService.updateConfig(configDtoUpdated);
        configService.clearCache();
        ConfigDto dbConfig2 = configService.getConfigByName(configDto.getName(), configDto.getGuildId());

        int configSize = configService.listGuildConfig("test").size();

        assertEquals(dbConfig1.getName(),configDto.getName());
        assertEquals(dbConfig1.getGuildId(),configDto.getGuildId());
        assertEquals(dbConfig2.getName(),configDtoUpdated.getName());
        assertEquals(dbConfig2.getGuildId(),configDtoUpdated.getGuildId());
        assertEquals(1, configSize);
        configService.deleteAll("test");
    }
}
