package toby.command.commands.misc

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.member
import toby.command.CommandTest.Companion.requestingUserDto
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

class UserInfoCommandTest : CommandTest {
    lateinit var userService: IUserService
    lateinit var userInfoCommand: UserInfoCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks() // Call the common setup defined in the interface

        // Additional setup specific to UserInfoCommandTest
        userService = mockk()
        userInfoCommand = UserInfoCommand(userService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testHandleCommandWithOwnUser() {
        // Mock the event's options to be empty
        every { event.options } returns listOf()

        // Mock the requesting user's DTO
        val userDto = UserDto(
            1, 1,
            superUser = true,
            musicPermission = true,
            digPermission = true,
            memePermission = true,
            socialCredit = 0,
            initiativeModifier = 0,
            musicDtos = emptyList<MusicDto>().toMutableList()
        )
        every { userService.getUserById(any(), any()) } returns userDto
        requestingUserDto.apply {
            every { musicDtos } returns listOf(MusicDto()).toMutableList()
        }

        // Test handle method
        userInfoCommand.handle(CommandContext(event), userDto, 0)

        // Verify interactions
        verify(exactly = 1) {
            event.hook.sendMessage("Here are the permissions for 'Effective Name': 'MUSIC: true, MEME: true, DIG: true, SUPERUSER: true'. \n" +
                    " There is no valid intro music file associated with user Effective Name.")
        }
        verify(exactly = 1) {
            userService.getUserById(any(), any())
        }
    }

    @Test
    fun testHandleCommandWithMentionedUserAndValidRequestingPermissions() {
        // Mock the requesting user's DTO
        val userDto = UserDto(
            1, 1,
            superUser = true,
            musicPermission = true,
            digPermission = true,
            memePermission = true,
            socialCredit = 0,
            initiativeModifier = 0,
            musicDtos = emptyList<MusicDto>().toMutableList()
        )

        // Mock the event's options to include mentions
        every { event.options } returns listOf(mockk<OptionMapping>())

        // Mock a mentioned user's DTO
        val mentionedUserDto = UserDto(6L, 1L)
        every { userService.getUserById(any(), any()) } returns userDto
        every { requestingUserDto.musicDtos } returns listOf(
            MusicDto(
                UserDto(1, 1),
                1,
                "filename",
                10,
                null
            )
        ).toMutableList()

        // Mock mentions
        val mentions = mockk<Mentions>()
        every { event.getOption(any())?.mentions } returns mentions
        val memberList = listOf(member)
        every { mentions.members } returns memberList
        every { userService.getUserById(any(), any()) } returns mentionedUserDto

        // Test handle method
        userInfoCommand.handle(CommandContext(event), requestingUserDto, 0)

        // Verify interactions
        verify(exactly = 1)
        {
            userService.getUserById(any(), any())
            event.hook.sendMessage(any<String>())
        }
    }

    @Test
    fun testHandleCommandNoPermission() {
        // Mock user interaction without permission
        every { userService.getUserById(any(), any()) } returns requestingUserDto
        every { requestingUserDto.superUser } returns false

        // Mock the event's options to include mentions
        every { event.options } returns listOf(mockk<OptionMapping>())

        // Mock the requesting user without permission
        // Test handle method
        userInfoCommand.handle(CommandContext(event), requestingUserDto, 0)

        // Verify interactions
        verify(exactly = 1) {
            event.hook.sendMessage("You do not have permission to view user permissions, if this is a mistake talk to the server owner")
        }
    }
}