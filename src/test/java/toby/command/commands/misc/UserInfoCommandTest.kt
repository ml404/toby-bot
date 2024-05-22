package toby.command.commands.misc

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

class UserInfoCommandTest : CommandTest {
    @Mock
    private lateinit var userService: IUserService

    private var userInfoCommand: UserInfoCommand? = null

    @BeforeEach
    fun setUp() {
        setUpCommonMocks() // Call the common setup defined in the interface

        // Additional setup specific to UserInfoCommandTest
        userService = Mockito.mock(IUserService::class.java)
        userInfoCommand = UserInfoCommand(userService)
        Mockito.`when`<Guild>(CommandTest.event.guild).thenReturn(
            Mockito.mock<Guild>(
                Guild::class.java
            )
        )
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun testHandleCommandWithOwnUser() {
        // Mock the event's options to be empty

        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf())

        // Mock the requesting user's DTO
        val requestingUserDto = UserDto()
        Mockito.`when`(userService.getUserById(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong()))
            .thenReturn(requestingUserDto)
        requestingUserDto.musicDto = MusicDto()
        Mockito.`when`(CommandTest.event.guild!!.idLong).thenReturn(123L)

        // Test handle method
        userInfoCommand!!.handle(CommandContext(CommandTest.event), requestingUserDto, 0)

        // Verify interactions
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.anyString(), ArgumentMatchers.eq(requestingUserDto))
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.anyString())
        Mockito.verify(userService, Mockito.times(0))
            .getUserById(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())
    }

    @Test
    fun testHandleCommandWithMentionedUserAndValidRequestingPermissions() {
        // Mock user interaction with mentioned user

        // Mock the event's options to include mentions

        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(
            listOf(
                Mockito.mock(
                    OptionMapping::class.java
                )
            )
        ) // Add an option to simulate mentions

        // Mock a mentioned user's DTO
        val mentionedUserDto = UserDto()
        Mockito.`when`(userService.getUserById(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong()))
            .thenReturn(mentionedUserDto)
        mentionedUserDto.musicDto = MusicDto()

        // Mock mentions
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption(ArgumentMatchers.anyString())).thenReturn(
            Mockito.mock<OptionMapping>(
                OptionMapping::class.java
            )
        )
        val mentions = Mockito.mock(Mentions::class.java)
        Mockito.`when`(CommandTest.event.getOption("users")!!.mentions).thenReturn(mentions)
        val mockMember = Mockito.mock(Member::class.java)
        val memberList = listOf(mockMember)
        Mockito.`when`(mentions.members).thenReturn(memberList)
        val mockGuild = Mockito.mock(Guild::class.java)
        Mockito.`when`(mockMember.guild).thenReturn(mockGuild)
        Mockito.`when`(mockGuild.idLong).thenReturn(123L)
        val mentionedUserMock = Mockito.mock(UserDto::class.java)
        Mockito.`when`(userService.getUserById(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong()))
            .thenReturn(mentionedUserMock)
        Mockito.`when`(mentionedUserMock.musicDto).thenReturn(MusicDto(1L, 1L, "filename", 10, null))
        Mockito.`when`(mockMember.effectiveName).thenReturn("Toby")


        // Test handle method
        val userDto = Mockito.mock(UserDto::class.java)
        Mockito.`when`(userDto.superUser).thenReturn(true)
        userInfoCommand!!.handle(CommandContext(CommandTest.event), userDto, 0)

        // Verify interactions

        //lookup on mentionedMember
        Mockito.verify(userService, Mockito.times(1))
            .getUserById(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong())
        //music file message
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())
        //mentioned user message
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.eq("Toby"),
            ArgumentMatchers.eq(mentionedUserMock)
        )
    }

    @Test
    fun testHandleCommandNoPermission() {
        // Mock user interaction without permission

        // Mock the event's options to include mentions

        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(
            listOf(
                Mockito.mock(
                    OptionMapping::class.java
                )
            )
        )

        // Mock the requesting user without permission
        val requestingUserDto = Mockito.mock(UserDto::class.java)
        Mockito.`when`(requestingUserDto.superUser).thenReturn(false)

        // Test handle method
        userInfoCommand!!.handle(CommandContext(CommandTest.event), requestingUserDto, 0)

        // Verify interactions
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.anyString())
    }
}