package database.service.impl

import database.DatabaseApplication
import database.configuration.TestDatabaseConfig
import database.dto.ExcuseDto
import database.service.IExcuseService
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
        DatabaseApplication::class,
        TestDatabaseConfig::class
    ]
)@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class ExcuseServiceImplIntegrationTest {
    @Autowired
    lateinit var excuseService: IExcuseService

    @BeforeEach
    fun setUp() {
        excuseService.clearCache()
    }

    @AfterEach
    fun tearDown() {
    }


    @Test
    fun testDataSQL() {
        Assertions.assertEquals(2, excuseService.listAllGuildExcuses(1L).size)
    }

    @Test
    fun whenValidIdAndGuild_thenExcuseShouldBeFound() {
        val excuseDto = ExcuseDto()
        excuseDto.guildId = 1L
        excuseDto.author = "Author"
        excuseDto.excuse = "Excuse"
        excuseDto.approved = false
        excuseService.createNewExcuse(excuseDto)
        val dbExcuse = excuseService.getExcuseById(excuseDto.id)

        Assertions.assertEquals(dbExcuse!!.id, excuseDto.id)
        Assertions.assertEquals(dbExcuse.guildId, excuseDto.guildId)
        Assertions.assertEquals(dbExcuse.author, excuseDto.author)
        Assertions.assertEquals(dbExcuse.excuse, excuseDto.excuse)
        Assertions.assertFalse(dbExcuse.approved)
        excuseService.deleteExcuseById(excuseDto.id)
    }

    @Test
    fun testUpdate_thenNewExcuseValuesShouldBeReturned() {
        var excuseDto1: ExcuseDto? = ExcuseDto()
        excuseDto1!!.guildId = 1L
        excuseDto1.author = "Author"
        excuseDto1.excuse = "Excuse"
        excuseDto1.approved = false

        excuseDto1 = excuseService.createNewExcuse(excuseDto1)
        val dbExcuse1 = excuseService.getExcuseById(excuseDto1!!.id)
        var pendingExcusesSize = excuseService.listPendingGuildExcuses(excuseDto1.guildId).size
        var approvedExcusesSize = excuseService.listApprovedGuildExcuses(excuseDto1.guildId).size


        Assertions.assertEquals(2, pendingExcusesSize)
        Assertions.assertEquals(1, approvedExcusesSize)
        Assertions.assertEquals(dbExcuse1!!.guildId, excuseDto1.guildId)
        Assertions.assertEquals(dbExcuse1.excuse, excuseDto1.excuse)
        Assertions.assertEquals(dbExcuse1.author, excuseDto1.author)
        Assertions.assertFalse(dbExcuse1.approved)


        var excuseDto2: ExcuseDto? = ExcuseDto()
        excuseDto2!!.id = excuseDto1.id
        excuseDto2.guildId = 1L
        excuseDto2.author = "Author"
        excuseDto2.excuse = "Excuse"
        excuseDto2.approved = true

        excuseDto2 = excuseService.updateExcuse(excuseDto2)
        excuseService.clearCache()
        val dbExcuse2 = excuseService.getExcuseById(excuseDto2!!.id)

        pendingExcusesSize = excuseService.listPendingGuildExcuses(excuseDto2.guildId).size
        approvedExcusesSize = excuseService.listApprovedGuildExcuses(excuseDto2.guildId).size

        Assertions.assertEquals(1, pendingExcusesSize)
        Assertions.assertEquals(2, approvedExcusesSize)
        Assertions.assertEquals(dbExcuse2!!.guildId, excuseDto2.guildId)
        Assertions.assertEquals(dbExcuse2.excuse, excuseDto2.excuse)
        Assertions.assertEquals(dbExcuse2.author, excuseDto2.author)
        Assertions.assertTrue(dbExcuse2.approved)
        excuseService.deleteExcuseById(excuseDto1.id)
    }
}
