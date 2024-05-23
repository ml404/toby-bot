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
import toby.jpa.dto.MusicDto
import toby.jpa.service.IMusicFileService
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Paths

@SpringBootTest(classes = [Application::class])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class MusicFileServiceImplIntegrationTest {
    @Autowired
    lateinit var musicFileService: IMusicFileService

    @BeforeEach
    fun setUp() {
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun whenValidDiscordIdAndGuild_thenUserShouldBeFound() {
        val musicDto1 = MusicDto()
        musicDto1.id = "1_1"
        musicDto1.fileName = "filename"
        musicDto1.musicBlob = "Some data".toByteArray()
        musicFileService.createNewMusicFile(musicDto1)
        val dbMusicDto1 = musicFileService.getMusicFileById(musicDto1.id)

        Assertions.assertEquals(dbMusicDto1!!.id, musicDto1.id)
        Assertions.assertEquals(dbMusicDto1.fileName, musicDto1.fileName)
        Assertions.assertArrayEquals(dbMusicDto1.musicBlob, musicDto1.musicBlob)
    }

    @Test
    fun testUpdate_thenNewUserValuesShouldBeReturned() {
        var musicDto1: MusicDto? = MusicDto()
        musicDto1!!.id = "1_1"
        musicDto1.fileName = "file 1"
        musicDto1.musicBlob = "some data 1".toByteArray()
        musicDto1 = musicFileService.createNewMusicFile(musicDto1)
        val dbMusicDto1 = musicFileService.getMusicFileById(musicDto1!!.id)

        Assertions.assertEquals(dbMusicDto1!!.id, musicDto1.id)
        Assertions.assertEquals(dbMusicDto1.fileName, musicDto1.fileName)
        Assertions.assertArrayEquals(dbMusicDto1.musicBlob, musicDto1.musicBlob)


        var musicDto2: MusicDto? = MusicDto()
        musicDto2!!.id = "1_1"
        musicDto2.fileName = "file 2"
        musicDto2.musicBlob = "some data 2".toByteArray()
        musicDto2 = musicFileService.updateMusicFile(musicDto2)
        val dbMusicDto2 = musicFileService.getMusicFileById(musicDto2!!.id)

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
        val musicData = Files.readAllBytes(Paths.get(mp3Resource.toURI()))

        val musicDto = MusicDto()
        musicDto.musicBlob = musicData

        musicDto.id = "1_1"
        musicDto.fileName = "filename"

        musicFileService.createNewMusicFile(musicDto)
        val dbMusicDto = musicFileService.getMusicFileById(musicDto.id)

        Assertions.assertEquals(dbMusicDto!!.id, musicDto.id)
        Assertions.assertEquals(dbMusicDto.fileName, musicDto.fileName)
        Assertions.assertArrayEquals(musicDto.musicBlob, dbMusicDto.musicBlob)
    }
}
