package toby.command.commands.moderation

import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

internal class SocialCreditCommandTest : CommandTest {
    var socialCreditCommand: SocialCreditCommand? = null

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
        Mockito.`when`(CommandTest.guild.isLoaded).thenReturn(false)


        //Act
        socialCreditCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("%s's social credit is: %d"),
            ArgumentMatchers.eq("Effective Name"),
            ArgumentMatchers.eq(0L)
        )
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndCorrectPermissions_printsRequestingUserDtoScore() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        Mockito.`when`(CommandTest.guild.isLoaded).thenReturn(false)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<User>(userOptionMapping.asUser).thenReturn(CommandTest.user)
        Mockito.`when`(userService.getUserById(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong()))
            .thenReturn(targetUserDto)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(true)


        //Act
        socialCreditCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("%s's social credit is: %d"),
            ArgumentMatchers.eq("UserName"),
            ArgumentMatchers.eq(0L)
        )
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndCorrectPermissionsAndValueToAdjust_printsAdjustingUserDtoScore() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        val scOptionMapping = Mockito.mock(OptionMapping::class.java)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        Mockito.`when`(CommandTest.guild.isLoaded).thenReturn(false)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("credit")).thenReturn(scOptionMapping)
        Mockito.`when`<User>(userOptionMapping.asUser).thenReturn(CommandTest.user)
        Mockito.`when`(scOptionMapping.asLong).thenReturn(5L)
        Mockito.`when`(userService.getUserById(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong()))
            .thenReturn(targetUserDto)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(true)
        Mockito.`when`(userService.updateUser(targetUserDto)).thenReturn(targetUserDto)
        Mockito.`when`(targetUserDto.socialCredit).thenReturn(5L)


        //Act
        socialCreditCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Updated user %s's social credit by %d. New score is: %d"),
            ArgumentMatchers.eq("UserName"),
            ArgumentMatchers.eq(5L),
            ArgumentMatchers.eq(5L)
        )
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndIncorrectPermissions_printsRequestingUserDtoScore() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        Mockito.`when`(CommandTest.guild.isLoaded).thenReturn(false)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<User>(userOptionMapping.asUser).thenReturn(CommandTest.user)
        Mockito.`when`(userService.getUserById(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong()))
            .thenReturn(targetUserDto)
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(false)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)


        //Act
        socialCreditCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("User '%s' is not allowed to adjust the social credit of user '%s'."),
            ArgumentMatchers.eq("Effective Name"),
            ArgumentMatchers.eq("UserName")
        )
    }

    @Test
    fun test_leaderboard_printsLeaderboard() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        Mockito.`when`(CommandTest.guild.isLoaded).thenReturn(false)
        val leaderOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("leaderboard"))
            .thenReturn(leaderOptionMapping)
        Mockito.`when`(leaderOptionMapping.asBoolean).thenReturn(true)
        Mockito.`when`(userService.getUserById(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong()))
            .thenReturn(targetUserDto)
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(false)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)
        Mockito.`when`(userService.listGuildUsers(1L))
            .thenReturn(listOf(CommandTest.requestingUserDto, targetUserDto))
        Mockito.`when`(CommandTest.requestingUserDto.socialCredit).thenReturn(100L)
        Mockito.`when`(targetUserDto.socialCredit).thenReturn(50L)
        Mockito.`when`(targetUserDto.discordId).thenReturn(2L)
        Mockito.`when`<List<Member>>(CommandTest.guild.members)
            .thenReturn(listOf(CommandTest.member, CommandTest.targetMember))
        Mockito.`when`(CommandTest.member.idLong).thenReturn(1L)
        Mockito.`when`(CommandTest.targetMember.idLong).thenReturn(2L)


        //Act
        socialCreditCommand!!.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.eq("**Social Credit Leaderboard**\n**-----------------------------**\n#1: Effective Name - score: 100\n#2: Target Effective Name - score: 50\n"))
    }
}