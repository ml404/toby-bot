import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertNull
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IMusicFileService
import toby.jpa.service.IUserService
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Paths

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MusicFileServiceImplTest {

    private lateinit var musicFileService: IMusicFileService
    private lateinit var userService: IUserService

    private val validUserDto = UserDto(1, 1)

    @BeforeEach
    fun setUp() {
        musicFileService = mockk(relaxed = true)
        userService = mockk(relaxed = true)

        // Mocking behavior
        every { musicFileService.createNewMusicFile(any()) } answers {
            firstArg()
        } andThen null

        // Mock getMusicFileById to return different MusicDto on consecutive calls
        val musicDto1 = MusicDto().apply {
            id = "1_1_1"
            fileName = "filename"
            musicBlob = "Some data".toByteArray()
            userDto = validUserDto
        }

        val musicDto2 = MusicDto().apply {
            id = "1_1_1"
            fileName = "filename 2"
            musicBlob = "Some data 2".toByteArray()
            userDto = validUserDto
        }

        every { musicFileService.getMusicFileById("1_1_1") }
            .returns(musicDto1) andThen musicDto2

        every { userService.deleteUserById(any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        // Reset mocks
        unmockkAll()
    }

    @Test
    fun whenValidDiscordIdAndGuild_thenUserShouldBeFound() {
        val musicDto1 = MusicDto().apply {
            id = "1_1_1"
            fileName = "filename"
            musicBlob = "Some data".toByteArray()
            userDto = validUserDto
        }

        // Act
        musicFileService.createNewMusicFile(musicDto1)
        val dbMusicDto1 = musicFileService.getMusicFileById(musicDto1.id!!)

        // Assert
        Assertions.assertEquals(dbMusicDto1!!.id, musicDto1.id)
        Assertions.assertEquals(dbMusicDto1.fileName, musicDto1.fileName)
        Assertions.assertArrayEquals(dbMusicDto1.musicBlob, musicDto1.musicBlob)
    }

    @Test
    fun testUpdate_thenNewUserValuesShouldBeReturned() {
        val musicDto1 = MusicDto().apply {
            id = "1_1_1"
            fileName = "filename"
            musicBlob = "Some data".toByteArray()
            userDto = validUserDto
        }
        musicFileService.createNewMusicFile(musicDto1)
        val dbMusicDto1 = musicFileService.getMusicFileById(musicDto1.id!!)

        Assertions.assertEquals(dbMusicDto1!!.id, musicDto1.id)
        Assertions.assertEquals(dbMusicDto1.fileName, musicDto1.fileName)
        Assertions.assertArrayEquals(dbMusicDto1.musicBlob, musicDto1.musicBlob)

        val musicDto2 = MusicDto().apply {
            id = "1_1_1"
            fileName = "filename 2"
            musicBlob = "Some data 2".toByteArray()
            userDto = validUserDto
        }
        musicFileService.createNewMusicFile(musicDto2)
        val dbMusicDto2 = musicFileService.getMusicFileById(musicDto2.id!!)

        Assertions.assertEquals(dbMusicDto2!!.id, musicDto2.id)
        Assertions.assertEquals(dbMusicDto2.fileName, musicDto2.fileName)
        Assertions.assertArrayEquals(dbMusicDto2.musicBlob, musicDto2.musicBlob)
    }

    //@Test
    //Basically h2 uses BLOB type for binaries, which means I need to change the musicDTO mapping specifically for test which would break the PROD mapping I have.
    //So this test exists but is kinda useless
    @Throws(IOException::class, URISyntaxException::class)
    fun musicDtoBlobSerializesAndDeserializesCorrectly() {
        val classLoader = javaClass.classLoader
        val mp3Resource = classLoader.getResource("test.mp3")
        val musicData = Files.readAllBytes(Paths.get(mp3Resource!!.toURI()))

        val musicDto = MusicDto().apply {
            id = "1_1_1"
            fileName = "filename"
            musicBlob = musicData
            userDto = validUserDto
        }

        // Act
        musicFileService.createNewMusicFile(musicDto)
        val dbMusicDto = musicFileService.getMusicFileById(musicDto.id!!)

        // Assert
        Assertions.assertEquals(dbMusicDto!!.id, musicDto.id)
        Assertions.assertEquals(dbMusicDto.fileName, musicDto.fileName)
        Assertions.assertArrayEquals(musicDto.musicBlob, dbMusicDto.musicBlob)
    }

    @Test
    fun `should not allow duplicate file upload for the same discordId and guildId`() {
        // Arrange
        val musicDto = MusicDto().apply {
            id = "1_1_1"
            fileName = "filename"
            musicBlob = "someBlob".toByteArray()
            userDto = UserDto(1234L, 5678L)
        }

        every { musicFileService.isFileAlreadyUploaded(musicDto) } returns true

        // Act & Assert
        musicFileService.createNewMusicFile(musicDto)
        assertNull(musicFileService.createNewMusicFile(musicDto))
    }
}
