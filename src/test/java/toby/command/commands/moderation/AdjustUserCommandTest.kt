package toby.command.commands.moderation

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
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

internal class AdjustUserCommandTest : CommandTest {
    private lateinit var adjustUserCommand: AdjustUserCommand
    
    @Mock
    var userService: IUserService = Mockito.mock(IUserService::class.java)

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        adjustUserCommand = AdjustUserCommand(userService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        Mockito.reset(userService)
    }

    @Test
    fun testAdjustUser_withCorrectPermissions_updatesTargetUser() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        val permissionOptionMapping = Mockito.mock(OptionMapping::class.java)
        val mentions = Mockito.mock(Mentions::class.java)
        Mockito.`when`(userService.getUserById(ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(targetUserDto)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("name")).thenReturn(permissionOptionMapping)
        Mockito.`when`(userOptionMapping.mentions).thenReturn(mentions)
        Mockito.`when`(permissionOptionMapping.asString).thenReturn(UserDto.Permissions.MUSIC.name)
        Mockito.`when`<List<Member>>(mentions.members).thenReturn(listOf(CommandTest.targetMember))

        //Act
        adjustUserCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(userService, Mockito.times(1)).getUserById(
            CommandTest.targetMember.idLong,
            CommandTest.targetMember.guild.idLong
        )
        Mockito.verify(userService, Mockito.times(1)).updateUser(targetUserDto)
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("Updated user %s's permissions"),
            ArgumentMatchers.eq("Target Effective Name")
        )
    }

    @Test
    fun testAdjustUser_withCorrectPermissions_createsTargetUser() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        val permissionOptionMapping = Mockito.mock(OptionMapping::class.java)
        val mentions = Mockito.mock(Mentions::class.java)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("name")).thenReturn(permissionOptionMapping)
        Mockito.`when`(userOptionMapping.mentions).thenReturn(mentions)
        Mockito.`when`(permissionOptionMapping.asString).thenReturn(UserDto.Permissions.MUSIC.name)
        Mockito.`when`<List<Member>>(mentions.members).thenReturn(listOf(CommandTest.targetMember))

        //Act
        adjustUserCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(userService, Mockito.times(1)).getUserById(
            CommandTest.targetMember.idLong,
            CommandTest.targetMember.guild.idLong
        )
        Mockito.verify(userService, Mockito.times(1)).createNewUser(ArgumentMatchers.any(UserDto::class.java))
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.eq("User %s's permissions did not exist in this server's database, they have now been created"),
            ArgumentMatchers.eq("Target Effective Name")
        )
    }

    @Test
    fun testAdjustUser_withNoMentionedPermissions_Errors() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        val mentions = Mockito.mock(Mentions::class.java)
        Mockito.`when`(userService.getUserById(ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(targetUserDto)
        Mockito.`when`(targetUserDto.guildId).thenReturn(1L)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`(userOptionMapping.mentions).thenReturn(mentions)
        Mockito.`when`<List<Member>>(mentions.members).thenReturn(listOf(CommandTest.targetMember))

        //Act
        adjustUserCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(userService, Mockito.times(0)).getUserById(
            CommandTest.targetMember.idLong,
            CommandTest.targetMember.guild.idLong
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("You must mention a permission to adjust of the user you've mentioned."))
    }

    @Test
    fun testAdjustUser_withNoMentionedUser_Errors() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`(userService.getUserById(ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(targetUserDto)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)

        //Act
        adjustUserCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(userService, Mockito.times(0)).getUserById(
            CommandTest.targetMember.idLong,
            CommandTest.targetMember.guild.idLong
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("You must mention 1 or more Users to adjust permissions of"))
    }

    @Test
    fun testAdjustUser_whenUserIsntOwner_Errors() {
        //Arrange
        val commandContext = CommandContext(CommandTest.event)
        val targetUserDto = Mockito.mock(UserDto::class.java)
        val userOptionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`(userService.getUserById(ArgumentMatchers.any(), ArgumentMatchers.any()))
            .thenReturn(targetUserDto)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("users")).thenReturn(userOptionMapping)
        Mockito.`when`(CommandTest.member.isOwner).thenReturn(false)
        Mockito.`when`(CommandTest.requestingUserDto.superUser).thenReturn(false)

        //Act
        adjustUserCommand.handle(commandContext, CommandTest.requestingUserDto, 0)

        //Assert
        Mockito.verify(userService, Mockito.times(0)).getUserById(
            CommandTest.targetMember.idLong,
            CommandTest.targetMember.guild.idLong
        )
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: Effective Name"))
    }
}