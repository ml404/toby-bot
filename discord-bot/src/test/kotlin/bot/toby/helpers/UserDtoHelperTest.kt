package bot.toby.helpers

import bot.toby.helpers.UserDtoHelper.Companion.produceMusicFileDataStringForPrinting
import database.dto.MusicDto
import database.dto.UserDto
import database.service.UserService
import io.mockk.*
import net.dv8tion.jda.api.entities.Member
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UserDtoHelperTest {

    private val userService: UserService = mockk(relaxed = true)
    private lateinit var userDtoHelper: UserDtoHelper

    @BeforeEach
    fun setUp() {
        userDtoHelper = UserDtoHelper(userService)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `calculateUserDto returns existing user when found in service`() {
        val existingUser = mockk<UserDto>()
        every { userService.getUserById(123L, 456L) } returns existingUser

        val result = userDtoHelper.calculateUserDto(123L, 456L)

        assertEquals(existingUser, result)
        verify(exactly = 0) { userService.createNewUser(any()) }
    }

    @Test
    fun `calculateUserDto creates new user when not found in service`() {
        every { userService.getUserById(123L, 456L) } returns null
        every { userService.createNewUser(any()) } returns mockk()

        val result = userDtoHelper.calculateUserDto(123L, 456L)

        assertNotNull(result)
        assertEquals(123L, result.discordId)
        assertEquals(456L, result.guildId)
        verify(exactly = 1) { userService.createNewUser(any()) }
    }

    @Test
    fun `calculateUserDto sets superUser flag when creating new user with isSuperUser=true`() {
        every { userService.getUserById(any(), any()) } returns null
        val capturedDto = slot<UserDto>()
        every { userService.createNewUser(capture(capturedDto)) } returns mockk()

        userDtoHelper.calculateUserDto(1L, 2L, isSuperUser = true)

        assertTrue(capturedDto.captured.superUser)
    }

    @Test
    fun `calculateUserDto does not set superUser when isSuperUser=false`() {
        every { userService.getUserById(any(), any()) } returns null
        val capturedDto = slot<UserDto>()
        every { userService.createNewUser(capture(capturedDto)) } returns mockk()

        userDtoHelper.calculateUserDto(1L, 2L, isSuperUser = false)

        assertFalse(capturedDto.captured.superUser)
    }

    @Test
    fun `userAdjustmentValidation returns true when requester is superUser and target is not`() {
        val requester = mockk<UserDto> { every { superUser } returns true }
        val target = mockk<UserDto> { every { superUser } returns false }

        assertTrue(userDtoHelper.userAdjustmentValidation(requester, target))
    }

    @Test
    fun `userAdjustmentValidation returns false when both are superUsers`() {
        val requester = mockk<UserDto> { every { superUser } returns true }
        val target = mockk<UserDto> { every { superUser } returns true }

        assertFalse(userDtoHelper.userAdjustmentValidation(requester, target))
    }

    @Test
    fun `userAdjustmentValidation returns false when requester is not superUser`() {
        val requester = mockk<UserDto> { every { superUser } returns false }
        val target = mockk<UserDto> { every { superUser } returns false }

        assertFalse(userDtoHelper.userAdjustmentValidation(requester, target))
    }

    @Test
    fun `updateUser delegates to userService`() {
        val userDto = mockk<UserDto>()
        every { userService.updateUser(userDto) } returns mockk()

        userDtoHelper.updateUser(userDto)

        verify(exactly = 1) { userService.updateUser(userDto) }
    }

    @Test
    fun `produceMusicFileDataStringForPrinting returns no-intro message when musicDtos is empty`() {
        val member = mockk<Member> { every { effectiveName } returns "Alice" }
        val userDto = mockk<UserDto> {
            every { musicDtos } returns mutableListOf()
        }

        val result = produceMusicFileDataStringForPrinting(member, userDto)

        assertTrue(result.contains("no valid intro music file"))
        assertTrue(result.contains("Alice"))
    }

    @Test
    fun `produceMusicFileDataStringForPrinting returns no-intro message when all musicDtos have blank filenames`() {
        val member = mockk<Member> { every { effectiveName } returns "Bob" }
        val dto = mockk<MusicDto> { every { fileName } returns ""; every { index } returns 0 }
        val userDto = mockk<UserDto> {
            every { musicDtos } returns mutableListOf(dto)
        }

        val result = produceMusicFileDataStringForPrinting(member, userDto)

        assertTrue(result.contains("no valid intro music file"))
    }

    @Test
    fun `produceMusicFileDataStringForPrinting returns formatted list when musicDtos has valid entries`() {
        val member = mockk<Member> { every { effectiveName } returns "Charlie" }
        val dto1 = mockk<MusicDto> {
            every { fileName } returns "song1.mp3"
            every { introVolume } returns 80
            every { index } returns 0
        }
        val dto2 = mockk<MusicDto> {
            every { fileName } returns "song2.mp3"
            every { introVolume } returns 50
            every { index } returns 1
        }
        val userDto = mockk<UserDto> {
            every { musicDtos } returns mutableListOf(dto1, dto2)
        }

        val result = produceMusicFileDataStringForPrinting(member, userDto)

        assertTrue(result.contains("song1.mp3"))
        assertTrue(result.contains("Volume: 80"))
        assertTrue(result.contains("song2.mp3"))
        assertTrue(result.contains("Volume: 50"))
    }
}
