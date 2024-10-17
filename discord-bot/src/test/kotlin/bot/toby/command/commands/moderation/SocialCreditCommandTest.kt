package bot.toby.command.commands.moderation

import database.service.IUserService
import bot.toby.command.CommandContext
import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.member
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.targetMember
import bot.toby.command.CommandTest.Companion.user
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.utils.concurrent.Task
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SocialCreditCommandTest : CommandTest {
    lateinit var socialCreditCommand: SocialCreditCommand

    private lateinit var userService: IUserService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        userService = mockk(relaxed = true)
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
        val commandContext = CommandContext(event)
        val task: Task<List<Member>> = mockk()
        val memberList = listOf(member, targetMember)
        val userOptionMapping = mockk<OptionMapping>()
        val creditOptionMapping = mockk<OptionMapping>()
        val leaderboardOptionMapping = mockk<OptionMapping>()


        every { userService.listGuildUsers(any<Long>()) } returns listOf(requestingUserDto)
        every { userService.getUserById(any<Long>(), any<Long>()) } returns requestingUserDto
        every { event.getOption("users") } returns userOptionMapping
        every { event.getOption("credit") } returns creditOptionMapping
        every { event.getOption("leaderboard") } returns leaderboardOptionMapping
        every { userOptionMapping.asUser } returns user
        every { creditOptionMapping.asLong } returns Long.MIN_VALUE
        every { leaderboardOptionMapping.asBoolean } returns false
        every { guild.isLoaded } returns false
        every { guild.loadMembers() } returns task
        every { member.isOwner } returns true
        every { task.get() } returns memberList
        every { user.effectiveName } returns "Effective Name"

        // Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage("Effective Name's social credit is: 0".trimIndent())
        }
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndCorrectPermissions_printsRequestingUserDtoScore() {
        // Arrange
        val commandContext = CommandContext(event)
        val userOptionMapping = mockk<OptionMapping>()
        val creditOptionMapping = mockk<OptionMapping>()
        val leaderboardOptionMapping = mockk<OptionMapping>()
        val task: Task<List<Member>> = mockk()
        val memberList = listOf(member, targetMember)
        every { guild.isLoaded } returns false
        every { guild.loadMembers() } returns task
        every { task.get() } returns memberList
        every { event.getOption("users") } returns userOptionMapping
        every { event.getOption("credit") } returns creditOptionMapping
        every { event.getOption("leaderboard") } returns leaderboardOptionMapping
        every { userOptionMapping.asUser } returns user
        every { creditOptionMapping.asLong } returns Long.MIN_VALUE
        every { leaderboardOptionMapping.asBoolean } returns false
        every { userService.getUserById(any(), any()) } returns requestingUserDto
        every { member.isOwner } returns true

        // Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage("UserName's social credit is: 0")
        }
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndCorrectPermissionsAndValueToAdjust_printsAdjustingUserDtoScore() {
        // Arrange
        val commandContext = CommandContext(event)
        val userOptionMapping = mockk<OptionMapping>()
        val scOptionMapping = mockk<OptionMapping>()
        val leaderboardOptionMapping = mockk<OptionMapping>()
        val targetUserDto = mockk<database.dto.UserDto>(relaxed = true)
        val task: Task<List<Member>> = mockk()
        val memberList = listOf(member, targetMember)
        every { guild.isLoaded } returns false
        every { guild.loadMembers() } returns task
        every { task.get() } returns memberList
        every { guild.isLoaded } returns false
        every { event.getOption("users") } returns userOptionMapping
        every { event.getOption("credit") } returns scOptionMapping
        every { event.getOption("leaderboard") } returns leaderboardOptionMapping
        every { userOptionMapping.asUser } returns user
        every { scOptionMapping.asLong } returns 5L
        every { leaderboardOptionMapping.asBoolean } returns false
        every { userService.getUserById(any(), any()) } returns targetUserDto
        every { targetUserDto.guildId } returns 1L
        every { member.isOwner } returns true
        every { userService.updateUser(targetUserDto) } returns targetUserDto
        every { targetUserDto.socialCredit } returns 5L

        // Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage("Updated user UserName's social credit by 5. New score is: 5".trimIndent())
        }
    }

    @Test
    fun test_socialCreditCommandWithUserMentionedAndIncorrectPermissions_printsRequestingUserDtoScore() {
        // Arrange
        val commandContext = CommandContext(event)
        val userOptionMapping = mockk<OptionMapping>(relaxed = true)
        val leaderboardOptionMapping = mockk<OptionMapping>()
        val targetUserDto = mockk<database.dto.UserDto>()
        val task: Task<List<Member>> = mockk()
        val memberList = listOf(member, targetMember)
        every { guild.isLoaded } returns false
        every { guild.loadMembers() } returns task
        every { task.get() } returns memberList
        every { guild.isLoaded } returns false
        every { event.getOption("users") } returns userOptionMapping
        every { event.getOption("leaderboard") } returns leaderboardOptionMapping
        every { userOptionMapping.asUser } returns user
        every { leaderboardOptionMapping.asBoolean } returns false
        every { userService.getUserById(any(), any()) } returns targetUserDto
        every { member.isOwner } returns false
        every { targetUserDto.guildId } returns 1L

        // Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage(
                "User 'Effective Name' is not allowed to adjust the social credit of user 'UserName'."
            )
        }
    }

    @Test
    fun test_leaderboard_printsLeaderboard() {
        // Arrange
        val commandContext = CommandContext(event)
        val targetUserDto = mockk<database.dto.UserDto>()
        val task: Task<List<Member>> = mockk()
        val memberList = listOf(member, targetMember)
        every { guild.isLoaded } returns false
        every { guild.loadMembers() } returns task
        every { task.get() } returns memberList
        val leaderOptionMapping = mockk<OptionMapping>()
        every { event.getOption("leaderboard") } returns leaderOptionMapping
        every { leaderOptionMapping.asBoolean } returns true
        every { userService.getUserById(any(), any()) } returns targetUserDto
        every { member.isOwner } returns false
        every { targetUserDto.guildId } returns 1L
        every { userService.listGuildUsers(1L) } returns listOf(requestingUserDto, targetUserDto)
        every { requestingUserDto.socialCredit } returns 100L
        every { targetUserDto.socialCredit } returns 50L
        every { targetUserDto.discordId } returns 2L
        every { guild.members } returns memberList
        every { guild.getMemberById(1L) } returns member
        every { guild.getMemberById(2L) } returns targetMember
        every { member.idLong } returns 1L
        every { targetMember.idLong } returns 2L


        // Arrange
        val expectedMessage = buildString {
            append("**Social Credit Leaderboard**\n")
            append("**-----------------------------**\n")
            append("#1: Effective Name - score: 100\n")
            append("#2: Target Effective Name - score: 50\n")
        }
        // Act
        socialCreditCommand.handle(commandContext, requestingUserDto, 0)

        // Assert
        verify(exactly = 1) {
            event.hook.sendMessage(expectedMessage.trimIndent())
        }
    }
}
