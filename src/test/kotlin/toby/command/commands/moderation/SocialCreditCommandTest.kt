package toby.command.commands.moderation

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.eq
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.guild
import toby.command.CommandTest.Companion.member
import toby.command.CommandTest.Companion.requestingUserDto
import toby.command.CommandTest.Companion.targetMember
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

internal class SocialCreditCommandTest : CommandTest {
    lateinit var socialCreditCommand: SocialCreditCommand

    @Mock
    var userService: IUserService = Mockito.mock(IUserService::class.java)

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        socialCreditCommand = SocialCreditCommand(userService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        Mockito.reset(userService)
    }


    @Test
    fun test_socialCreditCommandWithNoArgs_printsRequestingUserDtoScore() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        Mockito.`when`(guild.isLoaded).thenReturn(false)


        //Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            eq("%s's social credit is: %d"),
            eq("Effective Name"),
            eq(0L)
        )
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndCorrectPermissions_printsRequestingUserDtoScore() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        Mockito.`when`(guild.isLoaded).thenReturn(false)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`(userOptionMapping.asUser).thenReturn(CommandTest.user)
        Mockito.`when`(userService.getUserById(anyLong(), anyLong())).thenReturn(targetUserDto)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)
        Mockito.`when`(member.isOwner).thenReturn(true)


        //Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            eq("%s's social credit is: %d"),
            eq("UserName"),
            eq(0L)
        )
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndCorrectPermissionsAndValueToAdjust_printsAdjustingUserDtoScore() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        val scOptionMapping = Mockito.mock(OptionMapping::class.java)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        Mockito.`when`(guild.isLoaded).thenReturn(false)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("credit")).thenReturn(scOptionMapping)
        Mockito.`when`(userOptionMapping.asUser).thenReturn(CommandTest.user)
        Mockito.`when`(scOptionMapping.asLong).thenReturn(5L)
        Mockito.`when`(userService.getUserById(anyLong(), anyLong()))
            .thenReturn(targetUserDto)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)
        Mockito.`when`(member.isOwner).thenReturn(true)
        Mockito.`when`(userService.updateUser(targetUserDto)).thenReturn(targetUserDto)
        Mockito.`when`(targetUserDto.socialCredit).thenReturn(5L)


        //Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            eq("Updated user %s's social credit by %d. New score is: %d"),
            eq("UserName"),
            eq(5L),
            eq(5L)
        )
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndIncorrectPermissions_printsRequestingUserDtoScore() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        Mockito.`when`(guild.isLoaded).thenReturn(false)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`(userOptionMapping.asUser).thenReturn(CommandTest.user)
        Mockito.`when`(userService.getUserById(anyLong(), anyLong()))
            .thenReturn(targetUserDto)
        Mockito.`when`(member.isOwner).thenReturn(false)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)


        //Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            eq("User '%s' is not allowed to adjust the social credit of user '%s'."),
            eq("Effective Name"),
            eq("UserName")
        )
    }

    @Test
    fun test_leaderboard_printsLeaderboard() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        Mockito.`when`(guild.isLoaded).thenReturn(false)
        Mockito.`when`(guild.members).thenReturn(listOf(member, targetMember))
        val leaderOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("leaderboard")).thenReturn(leaderOptionMapping)
        Mockito.`when`(leaderOptionMapping.asBoolean).thenReturn(true)
        Mockito.`when`(userService.getUserById(anyLong(), anyLong())).thenReturn(targetUserDto)
        Mockito.`when`(member.isOwner).thenReturn(false)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)
        Mockito.`when`(userService.listGuildUsers(1L)).thenReturn(listOf(requestingUserDto, targetUserDto))
        Mockito.`when`(requestingUserDto.socialCredit).thenReturn(100L)
        Mockito.`when`(targetUserDto.socialCredit).thenReturn(50L)
        Mockito.`when`(targetUserDto.discordId).thenReturn(2L)
        Mockito.`when`<List<Member>>(guild.members).thenReturn(listOf(member, targetMember))
        Mockito.`when`(member.idLong).thenReturn(1L)
        Mockito.`when`(targetMember.idLong).thenReturn(2L)


        //Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(eq("**Social Credit Leaderboard**\n**-----------------------------**\n#1: Effective Name - score: 100\n#2: Target Effective Name - score: 50\n"))
    }
}