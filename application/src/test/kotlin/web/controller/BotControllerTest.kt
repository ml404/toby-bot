package web.controller

import database.dto.BrotherDto
import database.dto.ConfigDto
import database.dto.MusicDto
import database.dto.UserDto
import database.service.BrotherService
import database.service.ConfigService
import database.service.MusicFileService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

class BotControllerTest {

    private lateinit var controller: BotController
    private val userService: UserService = mockk(relaxed = true)
    private val musicFileService: MusicFileService = mockk(relaxed = true)
    private val configService: ConfigService = mockk(relaxed = true)
    private val brotherService: BrotherService = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        controller = BotController(userService, musicFileService, configService, brotherService)
    }

    // getBrother

    @Test
    fun `getBrother returns dto when found`() {
        val dto = mockk<BrotherDto>()
        every { brotherService.getBrotherById(123L) } returns dto

        val result = controller.getBrother("123")

        assertEquals(dto, result)
    }

    @Test
    fun `getBrother returns null when not found`() {
        every { brotherService.getBrotherById(any()) } returns null

        val result = controller.getBrother("999")

        assertNull(result)
    }

    // getConfig

    @Test
    fun `getConfig returns dto when found`() {
        val dto = mockk<ConfigDto>()
        every { configService.getConfigByName("volume", "456") } returns dto

        val result = controller.getConfig("volume", "456")

        assertEquals(dto, result)
    }

    @Test
    fun `getConfig returns null when not found`() {
        every { configService.getConfigByName(any(), any()) } returns null

        val result = controller.getConfig("missing", "456")

        assertNull(result)
    }

    @Test
    fun `getConfig handles null name`() {
        val dto = mockk<ConfigDto>()
        every { configService.getConfigByName(null, "456") } returns dto

        val result = controller.getConfig(null, "456")

        assertEquals(dto, result)
    }

    // getMusicBlob

    @Test
    fun `getMusicBlob returns 200 with audio content when blob found`() {
        val audioBytes = byteArrayOf(1, 2, 3)
        val musicFileDto = mockk<MusicDto> { every { musicBlob } returns audioBytes }
        every { musicFileService.getMusicFileById("abc") } returns musicFileDto

        val response = controller.getMusicBlob("abc")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(MediaType.parseMediaType("audio/mpeg"), response.headers.contentType)
        assertArrayEquals(audioBytes, response.body)
    }

    @Test
    fun `getMusicBlob returns 404 when file not found`() {
        every { musicFileService.getMusicFileById(any()) } returns null

        val response = controller.getMusicBlob("missing-id")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `getMusicBlob returns 404 when music blob is null`() {
        val musicFileDto = mockk<MusicDto> { every { musicBlob } returns null }
        every { musicFileService.getMusicFileById("abc") } returns musicFileDto

        val response = controller.getMusicBlob("abc")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    // getUser

    @Test
    fun `getUser returns dto when found`() {
        val dto = mockk<UserDto>()
        every { userService.getUserById(789L, 456L) } returns dto

        val result = controller.getUser(789L, 456L)

        assertEquals(dto, result)
    }

    @Test
    fun `getUser returns null when not found`() {
        every { userService.getUserById(any(), any()) } returns null

        val result = controller.getUser(999L, 456L)

        assertNull(result)
    }

    @Test
    fun `getUser handles null discordId and guildId`() {
        every { userService.getUserById(null, null) } returns null

        val result = controller.getUser(null, null)

        assertNull(result)
    }
}
