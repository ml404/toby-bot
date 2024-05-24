package toby.command.commands.moderation

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
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

    private lateinit var userService: IUserService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        userService = mockk()
        socialCreditCommand = SocialCreditCommand(userService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearMocks(userService)
    }

    @Test
    fun test_socialCreditCommandWithNoArgs_printsRequestingUserDtoScore() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        every { guild.isLoaded } returns false

        // Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageFormat("%s's social credit is: %d", "Effective Name", 0L) }
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndCorrectPermissions_printsRequestingUserDtoScore() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val userOptionMapping = mockk<OptionMapping>()
        val targetUserDto = mockk<UserDto>()
        every { guild.isLoaded } returns false
        every { CommandTest.event.getOption("users") } returns userOptionMapping
        every { userOptionMapping.asUser } returns CommandTest.user
        every { userService.getUserById(anyLong(), anyLong()) } returns targetUserDto
        every { targetUserDto.guildId } returns 1L
        every { member.isOwner } returns true

        // Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageFormat("%s's social credit is: %d", "UserName", 0L) }
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndCorrectPermissionsAndValueToAdjust_printsAdjustingUserDtoScore() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val userOptionMapping = mockk<OptionMapping>()
        val scOptionMapping = mockk<OptionMapping>()
        val targetUserDto = mockk<UserDto>()
        every { guild.isLoaded } returns false
        every { CommandTest.event.getOption("users") } returns userOptionMapping
        every { CommandTest.event.getOption("credit") } returns scOptionMapping
        every { userOptionMapping.asUser } returns CommandTest.user
        every { scOptionMapping.asLong } returns 5L
        every { userService.getUserById(anyLong(), anyLong()) } returns targetUserDto
        every { targetUserDto.guildId } returns 1L
        every { member.isOwner } returns true
        every { userService.updateUser(targetUserDto) } returns targetUserDto
        every { targetUserDto.socialCredit } returns 5L

        // Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageFormat("Updated user %s's social credit by %d. New score is: %d", "UserName", 5L, 5L) }
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndIncorrectPermissions_printsRequestingUserDtoScore() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val userOptionMapping = mockk<OptionMapping>()
        val targetUserDto = mockk<UserDto>()
        every { guild.isLoaded } returns false
        every { CommandTest.event.getOption("users") } returns userOptionMapping
        every { userOptionMapping.asUser } returns CommandTest.user
        every { userService.getUserById(anyLong(), anyLong()) } returns targetUserDto
        every { member.isOwner } returns false
        every { targetUserDto.guildId } returns 1L

        // Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) { CommandTest.interactionHook.sendMessageFormat("User '%s' is not allowed to adjust the social credit of user '%s'.", "Effective Name", "UserName") }
    }

    @Test
    fun test_leaderboard_printsLeaderboard() {
        // Arrange
        val commandContext = CommandContext(CommandTest.event)
        val targetUserDto = mockk<UserDto>()
        every { guild.isLoaded } returns false
        every { guild.members } returns listOf(member, targetMember)
        val leaderOptionMapping = mockk<OptionMapping>()
        every { CommandTest.event.getOption("leaderboard") } returns leaderOptionMapping
        every { leaderOptionMapping.asBoolean } returns true
        every { userService.getUserById(anyLong(), anyLong()) } returns targetUserDto
        every { member.isOwner } returns false
        every { targetUserDto.guildId } returns 1L
        every { userService.listGuildUsers(1L) } returns listOf(requestingUserDto, targetUserDto)
        every { requestingUserDto.socialCredit } returns 100L
        every { targetUserDto.socialCredit } returns 50L
        every { targetUserDto.discordId } returns 2L
        every { guild.members } returns listOf(member, targetMember)
        every { member.idLong } returns 1L
        every { targetMember.idLong } returns 2L

        // Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            CommandTest.interactionHook.sendMessageFormat(
                "**Social Credit Leaderboard**\n**-----------------------------**\n#1: Effective Name - score: 100\n#2: Target Effective Name - score: 50\n"
            )
        }
    }
}
