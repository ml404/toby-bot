package jpa

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import toby.Application
import toby.jpa.dto.ConfigDto
import toby.jpa.service.IConfigService

@SpringBootTest(classes = [Application::class])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class ConfigServiceImplIntegrationTest {
    @Autowired
    private val configService: IConfigService? = null


    @BeforeEach
    fun setUp() {
        configService!!.clearCache()
    }

    @AfterEach
    fun cleanUp() {
    }

    @Test
    fun testDataSQL() {
        Assertions.assertEquals(3, configService!!.listAllConfig()!!.size)
    }

    @Test
    fun whenValidNameAndGuild_thenConfigShouldBeFound() {
        val configDto = ConfigDto("TOKEN", "1234", "test")
        configService!!.createNewConfig(configDto)
        val dbConfig = configService.getConfigByName(configDto.name, configDto.guildId!!)

        Assertions.assertEquals(dbConfig!!.name, configDto.name)
        Assertions.assertEquals(dbConfig.guildId, configDto.guildId)
        configService.deleteAll("test")
    }

    @Test
    fun testUpdate_thenNewConfigShouldBeReturned() {
        val configDto = ConfigDto("TOKEN", "1234", "test")
        configService!!.createNewConfig(configDto)
        val dbConfig1 = configService.getConfigByName(configDto.name, configDto.guildId!!)

        val configDtoUpdated = ConfigDto("TOKEN", "12345", "test")
        configService.updateConfig(configDtoUpdated)
        configService.clearCache()
        val dbConfig2 = configService.getConfigByName(configDto.name, configDto.guildId!!)

        val configSize = configService.listGuildConfig("test")!!.size

        Assertions.assertEquals(dbConfig1!!.name, configDto.name)
        Assertions.assertEquals(dbConfig1.guildId, configDto.guildId)
        Assertions.assertEquals(dbConfig2!!.name, configDtoUpdated.name)
        Assertions.assertEquals(dbConfig2.guildId, configDtoUpdated.guildId)
        Assertions.assertEquals(1, configSize)
        configService.deleteAll("test")
    }
}
