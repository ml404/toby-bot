package toby.command.commands.misc

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import toby.command.CommandContext
import toby.command.CommandTest
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
        every { CommandTest.event.guild } returns mockk()
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testHandleCommandWithOwnUser() {
        // Mock the event's options to be empty
        every { CommandTest.event.options } returns listOf()

        // Mock the requesting user's DTO
        every { userService.getUserById(any(), any()) } returns requestingUserDto
        requestingUserDto.apply {
            every { musicDto } returns MusicDto()
        }
        every { CommandTest.event.guild!!.idLong } returns 123L

        // Test handle method
        userInfoCommand.handle(CommandContext(CommandTest.event), requestingUserDto, 0)

        // Verify interactions
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessage("There is no intro music file associated with your user.")
            CommandTest.interactionHook.sendMessage(anyString())
        }
        verify(exactly = 0) {
            userService.getUserById(any(), any())
        }
    }

    @Test
    fun testHandleCommandWithMentionedUserAndValidRequestingPermissions() {
        // Mock user interaction with mentioned user

        // Mock the event's options to include mentions
        every { CommandTest.event.options } returns listOf(mockk<OptionMapping>())

        // Mock a mentioned user's DTO
        val mentionedUserDto = UserDto()
        every { userService.getUserById(any(), any()) } returns mentionedUserDto
        mentionedUserDto.musicDto = MusicDto(1L, 1L, "filename", 10, null)

        // Mock mentions
        val mentions = mockk<Mentions>()
        every { CommandTest.event.getOption(any())?.mentions } returns mentions
        val mockMember = mockk<Member>()
        val memberList = listOf(mockMember)
        every { mentions.members } returns memberList
        val mockGuild = mockk<Guild>()
        every { mockMember.guild } returns mockGuild
        every { mockGuild.idLong } returns 123L
        every { userService.getUserById(any(), any()) } returns mentionedUserDto
        every { mockMember.effectiveName } returns "Toby"

        // Test handle method
        userInfoCommand.handle(CommandContext(CommandTest.event), requestingUserDto, 0)

        // Verify interactions
        verify(exactly = 1) {
            userService.getUserById(any(), any())
            CommandTest.interactionHook.sendMessageFormat(any(), any<String>())
            CommandTest.interactionHook.sendMessageFormat(any(), "Toby", mentionedUserDto)
        }
    }

    @Test
    fun testHandleCommandNoPermission() {
        // Mock user interaction without permission
        every { requestingUserDto.superUser } returns false

        // Mock the event's options to include mentions
        every { CommandTest.event.options } returns listOf(mockk<OptionMapping>())

        // Mock the requesting user without permission
        // Test handle method
        userInfoCommand.handle(CommandContext(CommandTest.event), requestingUserDto, 0)

        // Verify interactions
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessage("You do not have permission to view user permissions, if this is a mistake talk to the server owner)")
        }
    }
}