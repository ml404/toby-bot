package jpa

import org.apache.commons.collections4.IterableUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import toby.Application
import toby.jpa.dto.BrotherDto
import toby.jpa.service.IBrotherService

@SpringBootTest(classes = [Application::class])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class BrotherServiceImplIntegrationTest {
    @Autowired
    lateinit var brotherService: IBrotherService

    @BeforeEach
    fun setUp() {
        brotherService.clearCache()
        brotherService.deleteBrotherById(6L)
    }

    @AfterEach
    fun cleanDb() {
        brotherService.deleteBrotherById(6L)
    }

    @Test
    fun testDataSQL() {
        Assertions.assertEquals(3, IterableUtils.toList(brotherService!!.listBrothers()).size)
    }

    @Test
    fun whenValidDiscordId_thenBrotherShouldBeFound() {
        val brotherDto = BrotherDto(6L, "a")
        brotherService.createNewBrother(brotherDto)
        val dbBrother: BrotherDto? = brotherService.getBrotherById(brotherDto.discordId)

        assertNotNull(dbBrother)
        Assertions.assertEquals(dbBrother?.discordId, brotherDto.discordId)
        Assertions.assertEquals(dbBrother?.brotherName, brotherDto.brotherName)
        brotherService.deleteBrotherById(6L)
    }

    @Test
    fun testUpdate_thenNewBrotherShouldBeReturned() {
        val originalBrotherSize = IterableUtils.toList(brotherService!!.listBrothers()).size

        val brotherDto = BrotherDto(6L, "a")
        brotherService.createNewBrother(brotherDto)
        val dbBrother1: BrotherDto? = brotherService.getBrotherById(brotherDto.discordId)

        val brotherDtoUpdated = BrotherDto(6L, "b")
        brotherService.updateBrother(brotherDtoUpdated)
        brotherService.clearCache() // Clear the cache

        val expectedBrotherSize = originalBrotherSize + 1

        val dbBrother2: BrotherDto? = brotherService.getBrotherById(brotherDto.discordId)

        assertNotNull(dbBrother1)
        assertNotNull(dbBrother2)

        Assertions.assertEquals(dbBrother1?.discordId, brotherDto.discordId)
        Assertions.assertEquals(dbBrother1?.brotherName, brotherDto.brotherName)
        Assertions.assertEquals(dbBrother2?.discordId, brotherDtoUpdated.discordId)
        Assertions.assertEquals(dbBrother2?.brotherName, brotherDtoUpdated.brotherName)
        Assertions.assertEquals(expectedBrotherSize, originalBrotherSize + 1)
        brotherService.deleteBrotherById(6L)
    }
}
