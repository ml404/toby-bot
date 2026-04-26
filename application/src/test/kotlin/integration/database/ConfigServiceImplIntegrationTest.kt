package integration.database

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import database.dto.ConfigDto
import database.service.ConfigService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class,
        TestAppConfig::class,
        TestBotConfig::class,
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ConfigServiceImplIntegrationTest {
    @Autowired
    lateinit var configService: ConfigService


    @BeforeEach
    fun setUp() {
        configService.clearCache()
    }

    @AfterEach
    fun cleanUp() {
    }

    @Test
    fun testDataSQL() {
        Assertions.assertEquals(3, configService.listAllConfig()!!.size)
    }

    @Test
    fun whenValidNameAndGuild_thenConfigShouldBeFound() {
        val configDto = ConfigDto("TOKEN", "1234", "test")
        configService.createNewConfig(configDto)
        val dbConfig = configService.getConfigByName(configDto.name, configDto.guildId!!)

        Assertions.assertEquals(dbConfig!!.name, configDto.name)
        Assertions.assertEquals(dbConfig.guildId, configDto.guildId)
        configService.deleteAll("test")
    }

    @Test
    fun testUpdate_thenNewConfigShouldBeReturned() {
        val configDto = ConfigDto("TOKEN", "1234", "test")
        configService.createNewConfig(configDto)
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

    @Test
    fun upsertConfig_returnsCreated_andInsertsRow_whenNoneExists() {
        val result = configService.upsertConfig("TOKEN", "1234", "test")

        Assertions.assertTrue(result is ConfigService.UpsertResult.Created)
        configService.clearCache()
        val read = configService.getConfigByName("TOKEN", "test")
        Assertions.assertEquals("1234", read?.value)
        Assertions.assertEquals(1, configService.listGuildConfig("test")!!.size)
        configService.deleteAll("test")
    }

    @Test
    fun upsertConfig_returnsUpdated_withPreviousValue_whenRowExists() {
        configService.createNewConfig(ConfigDto("TOKEN", "1234", "test"))
        configService.clearCache()

        val result = configService.upsertConfig("TOKEN", "5678", "test")

        Assertions.assertTrue(result is ConfigService.UpsertResult.Updated)
        Assertions.assertEquals("1234", (result as ConfigService.UpsertResult.Updated).previousValue)
        configService.clearCache()
        Assertions.assertEquals("5678", configService.getConfigByName("TOKEN", "test")?.value)
        Assertions.assertEquals(1, configService.listGuildConfig("test")!!.size, "must not double-write")
        configService.deleteAll("test")
    }

    @Test
    fun upsertConfig_does_not_collide_across_guilds() {
        configService.createNewConfig(ConfigDto("TOKEN", "guildA-value", "guildA"))
        configService.clearCache()

        // Upserting the same name for a different guild creates a new row.
        val result = configService.upsertConfig("TOKEN", "guildB-value", "guildB")

        Assertions.assertTrue(result is ConfigService.UpsertResult.Created)
        configService.clearCache()
        Assertions.assertEquals("guildA-value", configService.getConfigByName("TOKEN", "guildA")?.value)
        Assertions.assertEquals("guildB-value", configService.getConfigByName("TOKEN", "guildB")?.value)
        configService.deleteAll("guildA")
        configService.deleteAll("guildB")
    }
}
