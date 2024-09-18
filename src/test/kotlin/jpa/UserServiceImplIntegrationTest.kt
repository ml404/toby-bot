package jpa

import configuration.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import toby.Application
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IMusicFileService
import toby.jpa.service.IUserService

@SpringBootTest(
    classes = [
        Application::class,
        TestAppConfig::class,
        TestBotConfig::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class UserServiceImplIntegrationTest {
    @Autowired
    lateinit var userService: IUserService

    @Autowired
    lateinit var musicService: IMusicFileService

    @BeforeEach
    fun setUp() {
        userService.clearCache()
    }

    @AfterEach
    fun tearDown() {
        userService.deleteUserById(6L, 1L)
    }

    @Test
    fun testDataSQL() {
        assertEquals(3, userService.listGuildUsers(1L).size)
    }

    @Test
    fun whenValidDiscordIdAndGuild_thenUserShouldBeFound() {
        val userDto = UserDto()
        userDto.discordId = 6L
        userDto.guildId = 1L
        val musicDto = MusicDto(userDto, 1, null, 0, null)
        userDto.musicDtos.add(musicDto)
        userService.createNewUser(userDto)
        val dbUser = userService.getUserById(userDto.discordId, userDto.guildId)

        assertEquals(dbUser!!.discordId, userDto.discordId)
        assertEquals(dbUser.guildId, userDto.guildId)
        assertTrue(dbUser.musicPermission)
        assertTrue(dbUser.memePermission)
        assertTrue(dbUser.digPermission)
        assertFalse(dbUser.superUser)
        assertNotNull(dbUser.musicDtos)
    }

    @Test
    fun testUpdate_thenNewUserValuesShouldBeReturned() {
        var userDto1: UserDto? = UserDto()
        userDto1!!.discordId = 6L
        userDto1.guildId = 1L
        val musicDto1 = MusicDto(userDto1)
        userDto1.musicDtos+=musicDto1
        userDto1 = userService.createNewUser(userDto1)
        val dbUser1 = userService.getUserById(userDto1.discordId, userDto1.guildId)

        val dbSize = userService.listGuildUsers(1L).size

        assertEquals(dbUser1!!.discordId, userDto1.discordId)
        assertEquals(dbUser1.guildId, userDto1.guildId)
        assertTrue(dbUser1.musicPermission)
        assertTrue(dbUser1.memePermission)
        assertTrue(dbUser1.digPermission)
        assertFalse(dbUser1.superUser)
        assertNotNull(dbUser1.musicDtos)

        var userDto2: UserDto? = UserDto()
        userDto2!!.discordId = 6L
        userDto2.guildId = 1L
        val musicDto2 = MusicDto(userDto2)
        userDto2.musicDtos += musicDto2
        userDto2.digPermission = false
        userDto2 = userService.updateUser(userDto2)
        userService.clearCache() // Clear the cache

        val dbUser2 = userService.getUserById(userDto2!!.discordId, userDto2.guildId)

        val guildMemberSize = userService.listGuildUsers(userDto2.guildId).size

        assertEquals(dbUser2!!.discordId, userDto2.discordId)
        assertEquals(dbUser2.guildId, userDto2.guildId)
        assertTrue(dbUser2.musicPermission)
        assertTrue(dbUser2.memePermission)
        assertFalse(dbUser2.digPermission)
        assertFalse(dbUser2.superUser)
        assertNotNull(dbUser2.musicDtos)
        assertEquals(dbSize, guildMemberSize)
    }


    @Test
    fun whenMusicFileExistsWithSameDiscordIdAndGuild_thenUserShouldBeFoundWithMusicFile() {
        val userDto = UserDto()
        userDto.discordId = 6L
        userDto.guildId = 1L
        val musicDto = MusicDto(userDto, 1, fileName = "test")
        userDto.musicDtos += musicDto
        userService.createNewUser(userDto)
        val dbUser = userService.getUserById(userDto.discordId, userDto.guildId)

        assertEquals(dbUser!!.discordId, userDto.discordId)
        assertEquals(dbUser.guildId, userDto.guildId)
        assertTrue(dbUser.musicPermission)
        assertTrue(dbUser.memePermission)
        assertTrue(dbUser.digPermission)
        assertFalse(dbUser.superUser)
        val dbMusicFileDto = userDto.musicDtos[0]
        assertNotNull(dbMusicFileDto)
        assertEquals(dbMusicFileDto.id, musicDto.id)
        assertEquals(dbMusicFileDto.fileName, musicDto.fileName)
    }

    @Test
    fun whenMusicFileExistsWithSameDiscordIdAndGuildAndUpdatedOnce_thenUserShouldBeFoundWithMusicFile() {
        val userDto = UserDto()
        userDto.discordId = 6L
        userDto.guildId = 1L
        val musicDto = MusicDto(userDto, 0, null)
        userDto.musicDtos += musicDto
        userService.createNewUser(userDto)
        val dbUser = userService.getUserById(userDto.discordId, userDto.guildId)
        userService.clearCache()

        assertEquals(dbUser!!.discordId, userDto.discordId)
        assertEquals(dbUser.guildId, userDto.guildId)
        assertTrue(dbUser.musicPermission)
        assertTrue(dbUser.memePermission)
        assertTrue(dbUser.digPermission)
        assertFalse(dbUser.superUser)
        var dbMusicFileDto = userDto.musicDtos[0]
        assertNotNull(dbMusicFileDto)
        assertEquals(dbMusicFileDto.id, musicDto.id)
        assertEquals(dbMusicFileDto.fileName, musicDto.fileName)


        dbMusicFileDto.fileName = "file name"
        dbMusicFileDto.musicBlob = "test data".toByteArray()
        userDto.musicDtos[0] = dbMusicFileDto
        val dbUser2 = userService.updateUser(userDto)


        assertEquals(dbUser2!!.discordId, userDto.discordId)
        assertEquals(dbUser2.guildId, userDto.guildId)
        assertTrue(dbUser2.musicPermission)
        assertTrue(dbUser2.memePermission)
        assertTrue(dbUser2.digPermission)
        assertFalse(dbUser2.superUser)
        dbMusicFileDto = dbUser2.musicDtos[0]
        assertNotNull(dbMusicFileDto)
        assertEquals(dbMusicFileDto.id, musicDto.id)
        assertEquals(dbMusicFileDto.fileName, musicDto.fileName)
    }

    @Test
    fun whenMusicFileExistsWithSameDiscordIdAndGuildAndUserIsDeleted_thenMusicFileShouldDeleteToo() {
        val userDto = UserDto()
        userDto.discordId = 6L
        userDto.guildId = 1L
        val musicDto = MusicDto(userDto, 0, null)
        userDto.musicDtos += musicDto
        userService.createNewUser(userDto)
        val dbUser = userService.getUserById(userDto.discordId, userDto.guildId)
        userService.clearCache()

        assertEquals(dbUser!!.discordId, userDto.discordId)
        assertEquals(dbUser.guildId, userDto.guildId)
        assertTrue(dbUser.musicPermission)
        assertTrue(dbUser.memePermission)
        assertTrue(dbUser.digPermission)
        assertFalse(dbUser.superUser)
        val dbMusicFileDto = userDto.musicDtos[0]
        assertNotNull(dbMusicFileDto)
        assertEquals(dbMusicFileDto.id, musicDto.id)
        assertEquals(dbMusicFileDto.fileName, musicDto.fileName)

        userService.deleteUserById(6,1)

        val musicDtoFromDb = musicService.getMusicFileById(userDto.musicDtos[0].id!!)

        assertEquals(null, musicDtoFromDb)

    }
}
