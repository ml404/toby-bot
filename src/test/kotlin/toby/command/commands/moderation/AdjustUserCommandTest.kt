package toby.command.commands.moderation

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import toby.Application
import toby.command.CommandContext
import toby.command.CommandTest
import toby.command.CommandTest.Companion.event
import toby.command.CommandTest.Companion.requestingUserDto
import toby.command.CommandTest.Companion.targetMember
import toby.helpers.UserDtoHelper
import toby.jpa.dto.UserDto
import toby.jpa.service.IUserService

@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test")
internal class AdjustUserCommandTest : CommandTest {
    private lateinit var adjustUserCommand: AdjustUserCommand

    private val userService: IUserService = mockk()

    @Autowired
    lateinit var userDtoHelper: UserDtoHelper

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        adjustUserCommand = AdjustUserCommand(userService, userDtoHelper)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    @Test
    fun testAdjustUser_withCorrectPermissions_updatesTargetUser() {
        // Arrange
        val commandContext = CommandContext(event)
        val targetUserDto = mockk<UserDto>(relaxed = true)
        val userOptionMapping = mockk<OptionMapping>()
        val permissionOptionMapping = mockk<OptionMapping>()
        val modifier = mockk<OptionMapping>()
        val mentions = mockk<Mentions>()

        every { userService.getUserById(any(), any()) } returns targetUserDto
        every { userService.updateUser(any()) } returns targetUserDto
        every { targetUserDto.guildId } returns 1L
        every { event.getOption("users") } returns userOptionMapping
        every { event.getOption("name") } returns permissionOptionMapping
        every { event.getOption("modifier") } returns modifier
        every { userOptionMapping.mentions } returns mentions
        every { permissionOptionMapping.asString } returns UserDto.Permissions.MUSIC.name
        every { mentions.members } returns listOf(targetMember)
        every { modifier.asInt } returns 1

        // Act
        adjustUserCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { userService.getUserById(any(), any()) }
        verify(exactly = 1) { userService.updateUser(targetUserDto) }
        verify(exactly = 1) {
            event.hook.sendMessageFormat(
                "Updated user %s's permissions",
                "Target Effective Name"
            )
        }
    }

    @Test
    fun testAdjustUser_withCorrectPermissions_createsTargetUser() {
        // Arrange
        val commandContext = CommandContext(event)
        val targetUserDto = mockk<UserDto>()
        val userOptionMapping = mockk<OptionMapping>()
        val permissionOptionMapping = mockk<OptionMapping>()
        val mentions = mockk<Mentions>()

        every { targetUserDto.guildId } returns 1L
        every { event.getOption("users") } returns userOptionMapping
        every { event.getOption("name") } returns permissionOptionMapping
        every { userOptionMapping.mentions } returns mentions
        every { permissionOptionMapping.asString } returns UserDto.Permissions.MUSIC.name
        every { mentions.members } returns listOf(targetMember)
        every { userService.getUserById(any(), any()) } returns null
        every { userService.createNewUser(any()) } returns targetUserDto

        // Act
        adjustUserCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { userService.getUserById(any(), any()) }
        verify(exactly = 1) { userService.createNewUser(any()) }
        verify(exactly = 1) {
            event.hook.sendMessageFormat(
                "User %s's permissions did not exist in this server's database, they have now been created",
                "Target Effective Name"
            )
        }
    }

    @Test
    fun testAdjustUser_withNoMentionedPermissions_Errors() {
        // Arrange
        val commandContext = CommandContext(event)
        val targetUserDto = mockk<UserDto>()
        val userOptionMapping = mockk<OptionMapping>()
        val mentions = mockk<Mentions>()

        every { userService.getUserById(any(), any()) } returns targetUserDto
        every { targetUserDto.guildId } returns 1L
        every { event.getOption("users") } returns userOptionMapping
        every { userOptionMapping.mentions } returns mentions
        every { mentions.members } returns listOf(targetMember)
        every { event.getOption("name") } returns null

        // Act
        adjustUserCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 0) { userService.getUserById(any(), any()) }
        verify(exactly = 1) {
            event.hook.sendMessage("You must mention a permission to adjust for the user you've mentioned.")
        }
    }

    @Test
    fun testAdjustUser_withNoMentionedUser_Errors() {
        // Arrange
        val commandContext = CommandContext(event)
        val targetUserDto = mockk<UserDto>()
        val userOptionMapping = mockk<OptionMapping>()

        every { userService.getUserById(any(), any()) } returns targetUserDto
        every { event.getOption("users") } returns userOptionMapping
        every { userOptionMapping.mentions } returns mockk<Mentions>(relaxed = true)

        // Act
        adjustUserCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 0) { userService.getUserById(any(), any()) }
        verify(exactly = 1) {
            event.hook.sendMessage("You must mention 1 or more Users to adjust permissions of")
        }
    }

    @Test
    fun testAdjustUser_whenUserIsntOwner_Errors() {
        // Arrange
        val commandContext = CommandContext(event)
        val targetUserDto = mockk<UserDto>()
        val userOptionMapping = mockk<OptionMapping>()

        every { userService.getUserById(any(), any()) } returns targetUserDto
        every { event.getOption("users") } returns userOptionMapping
        every { CommandTest.member.isOwner } returns false
        every { requestingUserDto.superUser } returns false

        // Act
        adjustUserCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 0) { userService.getUserById(any(), any()) }
        verify(exactly = 1) {
            event.hook.sendMessage("You do not have adequate permissions to use this command, if you believe this is a mistake talk to Effective Name")
        }
    }
}
