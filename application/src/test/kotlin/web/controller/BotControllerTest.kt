package web.controller

import common.media.MediaToken
import database.dto.social.BrotherDto
import database.dto.guild.ConfigDto
import database.dto.music.MusicDto
import database.dto.user.UserDto
import database.service.social.BrotherService
import database.service.guild.ConfigService
import database.service.music.MusicFileService
import database.service.user.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.Instant

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
    //
    // The endpoint is anonymous — lavaplayer fetches stored intro audio with
    // no session — so a signed token is the only thing standing between an id
    // of the form `guildId_discordId_index` and every uploaded MP3 on the bot.

    /** Signs [id] the way the player and the intros page both do. */
    private fun signed(id: String): Pair<Long, String> {
        val expiry = MediaToken.expiryFor(Instant.now())
        return expiry to MediaToken.sign(id, expiry)
    }

    @Test
    fun `getMusicBlob returns 200 with audio content for a signed request`() {
        val audioBytes = byteArrayOf(1, 2, 3)
        val musicFileDto = mockk<MusicDto> { every { musicBlob } returns audioBytes }
        every { musicFileService.getMusicFileById("abc") } returns musicFileDto
        val (expiry, signature) = signed("abc")

        val response = controller.getMusicBlob("abc", expiry, signature)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(MediaType.parseMediaType("audio/mpeg"), response.headers.contentType)
        assertArrayEquals(audioBytes, response.body)
    }

    @Test
    fun `getMusicBlob refuses an unsigned request`() {
        val musicFileDto = mockk<MusicDto> { every { musicBlob } returns byteArrayOf(1, 2, 3) }
        every { musicFileService.getMusicFileById("abc") } returns musicFileDto

        val response = controller.getMusicBlob("abc")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNull(response.body)
    }

    @Test
    fun `getMusicBlob refuses a token minted for a different intro`() {
        val musicFileDto = mockk<MusicDto> { every { musicBlob } returns byteArrayOf(1, 2, 3) }
        every { musicFileService.getMusicFileById(any()) } returns musicFileDto
        val (expiry, signature) = signed("1_2_1")

        val response = controller.getMusicBlob("1_2_2", expiry, signature)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `getMusicBlob refuses an expired token`() {
        val musicFileDto = mockk<MusicDto> { every { musicBlob } returns byteArrayOf(1, 2, 3) }
        every { musicFileService.getMusicFileById("abc") } returns musicFileDto
        val expired = Instant.now().epochSecond - 1

        val response = controller.getMusicBlob("abc", expired, MediaToken.sign("abc", expired))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `getMusicBlob does not hit the database for an unsigned request`() {
        // The refusal is cheap on purpose: an unauthenticated scraper walking
        // ids shouldn't be able to make the bot read blobs out of Postgres.
        controller.getMusicBlob("abc")

        verify(exactly = 0) { musicFileService.getMusicFileById(any()) }
    }

    @Test
    fun `getMusicBlob returns 404 when file not found`() {
        every { musicFileService.getMusicFileById(any()) } returns null
        val (expiry, signature) = signed("missing-id")

        val response = controller.getMusicBlob("missing-id", expiry, signature)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `getMusicBlob returns 404 when music blob is null`() {
        val musicFileDto = mockk<MusicDto> { every { musicBlob } returns null }
        every { musicFileService.getMusicFileById("abc") } returns musicFileDto
        val (expiry, signature) = signed("abc")

        val response = controller.getMusicBlob("abc", expiry, signature)

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
