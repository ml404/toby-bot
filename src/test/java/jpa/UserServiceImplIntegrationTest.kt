package jpa

import org.apache.commons.collections4.IterableUtils
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
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

@SpringBootTest(classes = [Application::class])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
class UserServiceImplIntegrationTest {
    @Autowired
    private val userService: IUserService? = null

    @BeforeEach
    fun setUp() {
        userService!!.clearCache()
    }

    @AfterEach
    fun tearDown() {
    }

    @Test
    fun testDataSQL() {
        Assertions.assertEquals(3, IterableUtils.toList(userService!!.listGuildUsers(1L)).size)
    }

    @Test
    fun whenValidDiscordIdAndGuild_thenUserShouldBeFound() {
        val userDto = UserDto()
        userDto.discordId = 6L
        userDto.guildId = 1L
        val musicDto = MusicDto(userDto.discordId!!, userDto.guildId, null, 0, null)
        userDto.musicDto = musicDto
        userService!!.createNewUser(userDto)
        val dbUser = userService.getUserById(userDto.discordId, userDto.guildId)

        Assertions.assertEquals(dbUser!!.discordId, userDto.discordId)
        Assertions.assertEquals(dbUser.guildId, userDto.guildId)
        Assertions.assertTrue(dbUser.musicPermission)
        Assertions.assertTrue(dbUser.memePermission)
        Assertions.assertTrue(dbUser.digPermission)
        Assertions.assertFalse(dbUser.superUser)
        Assertions.assertNotNull(dbUser.musicDto)
        userService.deleteUserById(6L, 1L)
    }

    @Test
    fun testUpdate_thenNewUserValuesShouldBeReturned() {
        var userDto1: UserDto? = UserDto()
        userDto1!!.discordId = 6L
        userDto1.guildId = 1L
        val musicDto1 = MusicDto(userDto1.discordId!!, userDto1.guildId, null, 0, null)
        userDto1.musicDto = musicDto1
        userDto1 = userService!!.createNewUser(userDto1)
        val dbUser1 = userService.getUserById(userDto1!!.discordId, userDto1.guildId)

        val dbSize = userService.listGuildUsers(1L).size

        Assertions.assertEquals(dbUser1!!.discordId, userDto1.discordId)
        Assertions.assertEquals(dbUser1.guildId, userDto1.guildId)
        Assertions.assertTrue(dbUser1.musicPermission)
        Assertions.assertTrue(dbUser1.memePermission)
        Assertions.assertTrue(dbUser1.digPermission)
        Assertions.assertFalse(dbUser1.superUser)
        Assertions.assertNotNull(dbUser1.musicDto)

        var userDto2: UserDto? = UserDto()
        userDto2!!.discordId = 6L
        userDto2.guildId = 1L
        val musicDto2 = MusicDto(userDto2.discordId!!, userDto2.guildId, null, 0, null)
        userDto2.musicDto = musicDto2
        userDto2.digPermission = false
        userDto2 = userService.updateUser(userDto2)
        userService.clearCache() // Clear the cache

        val dbUser2 = userService.getUserById(userDto2!!.discordId, userDto2.guildId)

        val guildMemberSize = userService.listGuildUsers(userDto2.guildId).size

        Assertions.assertEquals(dbUser2!!.discordId, userDto2.discordId)
        Assertions.assertEquals(dbUser2.guildId, userDto2.guildId)
        Assertions.assertTrue(dbUser2.musicPermission)
        Assertions.assertTrue(dbUser2.memePermission)
        Assertions.assertFalse(dbUser2.digPermission)
        Assertions.assertFalse(dbUser2.superUser)
        Assertions.assertNotNull(dbUser2.musicDto)
        Assertions.assertEquals(dbSize, guildMemberSize)
        userService.deleteUserById(6L, 1L)
    }


    @Test
    fun whenMusicFileExistsWithSameDiscordIdAndGuild_thenUserShouldBeFoundWithMusicFile() {
        val userDto = UserDto()
        userDto.discordId = 6L
        userDto.guildId = 1L
        val musicDto = MusicDto(userDto.discordId!!, userDto.guildId, "test", 0, null)
        userDto.musicDto = musicDto
        userService!!.createNewUser(userDto)
        val dbUser = userService.getUserById(userDto.discordId, userDto.guildId)

        Assertions.assertEquals(dbUser!!.discordId, userDto.discordId)
        Assertions.assertEquals(dbUser.guildId, userDto.guildId)
        Assertions.assertTrue(dbUser.musicPermission)
        Assertions.assertTrue(dbUser.memePermission)
        Assertions.assertTrue(dbUser.digPermission)
        Assertions.assertFalse(dbUser.superUser)
        val dbMusicFileDto = userDto.musicDto
        Assertions.assertNotNull(dbMusicFileDto)
        Assertions.assertEquals(dbMusicFileDto!!.id, musicDto.id)
        Assertions.assertEquals(dbMusicFileDto.fileName, musicDto.fileName)
        userService.deleteUserById(6L, 1L)
    }

    @Test
    fun whenMusicFileExistsWithSameDiscordIdAndGuildAndUpdatedOnce_thenUserShouldBeFoundWithMusicFile() {
        val userDto = UserDto()
        userDto.discordId = 6L
        userDto.guildId = 1L
        val musicDto = MusicDto(userDto.discordId!!, userDto.guildId, null, 0, null)
        userDto.musicDto = musicDto
        userService!!.createNewUser(userDto)
        val dbUser = userService.getUserById(userDto.discordId, userDto.guildId)
        userService.clearCache()

        Assertions.assertEquals(dbUser!!.discordId, userDto.discordId)
        Assertions.assertEquals(dbUser.guildId, userDto.guildId)
        Assertions.assertTrue(dbUser.musicPermission)
        Assertions.assertTrue(dbUser.memePermission)
        Assertions.assertTrue(dbUser.digPermission)
        Assertions.assertFalse(dbUser.superUser)
        var dbMusicFileDto = userDto.musicDto
        Assertions.assertNotNull(dbMusicFileDto)
        Assertions.assertEquals(dbMusicFileDto!!.id, musicDto.id)
        Assertions.assertEquals(dbMusicFileDto.fileName, musicDto.fileName)


        dbMusicFileDto.fileName = "file name"
        dbMusicFileDto.musicBlob = "test data".toByteArray()
        userDto.musicDto = dbMusicFileDto
        val dbUser2 = userService.updateUser(userDto)


        Assertions.assertEquals(dbUser2!!.discordId, userDto.discordId)
        Assertions.assertEquals(dbUser2.guildId, userDto.guildId)
        Assertions.assertTrue(dbUser2.musicPermission)
        Assertions.assertTrue(dbUser2.memePermission)
        Assertions.assertTrue(dbUser2.digPermission)
        Assertions.assertFalse(dbUser2.superUser)
        dbMusicFileDto = dbUser2.musicDto
        Assertions.assertNotNull(dbMusicFileDto)
        Assertions.assertEquals(dbMusicFileDto!!.id, musicDto.id)
        Assertions.assertEquals(dbMusicFileDto.fileName, musicDto.fileName)
        userService.deleteUserById(6L, 1L)
    }
}
