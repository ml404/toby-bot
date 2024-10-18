package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import database.dto.ExcuseDto
import database.service.ExcuseService
import io.mockk.*
import io.mockk.InternalPlatformDsl.toStr
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ExcuseCommandTest : CommandTest {
    lateinit var excuseCommand: ExcuseCommand

    lateinit var excuseService: ExcuseService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        excuseService = mockk()
        excuseCommand = ExcuseCommand(excuseService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun aRandomApprovedExcuse_WhenNoOptionsUsed() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val userDto = mockk<database.dto.UserDto>()
        val deleteDelay = 0

        // Mock the behavior of the excuseService when listing approved guild excuses
        val excuseDto = ExcuseDto(1, 1L, "TestAuthor", "Excuse 1", true)
        val excuseDtos: List<ExcuseDto?> = listOf(excuseDto)
        val optionMapping = mockk<OptionMapping>()
        every { event.options } returns emptyList()
        every { event.getOption("action") } returns optionMapping
        every { optionMapping.asString } returns "all"
        every { excuseService.listApprovedGuildExcuses(any()) } returns excuseDtos

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify {
            event.hook.sendMessageFormat(
                "Excuse #%d: '%s' - %s.",
                excuseDto.id,
                excuseDto.excuse,
                excuseDto.author
            )
        }
    }

    @Test
    fun listAllApprovedExcuses_WithValidApprovedOnes() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val userDto = mockk<database.dto.UserDto>()
        val deleteDelay = 0

        // Mock the behavior of the excuseService when listing approved guild excuses
        val excuseDtos: List<ExcuseDto?> = listOf(
            ExcuseDto(1, 1L, "TestAuthor", "Excuse 1", true),
            ExcuseDto(2, 1L, "TestAuthor", "Excuse 2", true),
            ExcuseDto(3, 1L, "TestAuthor", "Excuse 3", true)
        )
        val optionMapping = mockk<OptionMapping>()
        every { event.options } returns listOf(optionMapping)
        every { event.getOption("action") } returns optionMapping
        every { optionMapping.asString } returns "all"
        every { excuseService.listApprovedGuildExcuses(any()) } returns excuseDtos

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun listAllApprovedExcuses_WithNoValidApprovedOnes() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val userDto = mockk<database.dto.UserDto>()
        val deleteDelay = 0

        // Mock the behavior of the excuseService when listing approved guild excuses
        val optionMapping = mockk<OptionMapping>()
        every { event.options } returns emptyList()
        every { event.getOption("action") } returns optionMapping
        every { optionMapping.asString } returns "all"
        every { excuseService.listApprovedGuildExcuses(any()) } returns emptyList<ExcuseDto>()

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify { event.hook.sendMessage("There are no approved excuses, consider submitting some.") }
    }

    @Test
    fun createNewExcuse() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val userDto = mockk<database.dto.UserDto>()
        val deleteDelay = 0
        val excuseToCreate = ExcuseDto(1, 1L, "UserName", "Excuse 1", false)
        val excuseMapping = mockk<OptionMapping>()
        val actionMapping = mockk<OptionMapping>()
        val authorMapping = mockk<OptionMapping>()
        every { event.getOption("excuse") } returns excuseMapping
        every { event.getOption("action") } returns actionMapping
        every { event.getOption("author") } returns authorMapping
        every { event.options } returns listOf(excuseMapping)
        every { actionMapping.asString } returns null.toStr()
        every { excuseMapping.asString } returns "Excuse 1"
        every { authorMapping.asMember } returns null
        every { excuseService.listAllGuildExcuses(1L) } returns emptyList<ExcuseDto>()
        every { excuseService.createNewExcuse(any()) } returns excuseToCreate

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify {
            excuseService.listAllGuildExcuses(1L)
            excuseService.createNewExcuse(ExcuseDto(null, 1L, "UserName", "Excuse 1", false))
            event.hook.sendMessageFormat(any(), excuseToCreate.excuse, excuseToCreate.author, excuseToCreate.id)
        }
    }

    @Test
    fun createNewExcuse_thatExists_throwsError() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val userDto = mockk<database.dto.UserDto>()
        val deleteDelay = 0

        val excuseToCreate = ExcuseDto(1, 1L, "UserName", "Excuse 1", false)
        val excuseDtos: List<ExcuseDto?> = listOf(excuseToCreate)
        val excuseMapping = mockk<OptionMapping>()
        val actionMapping = mockk<OptionMapping>()
        val authorMapping = mockk<OptionMapping>()
        every { event.getOption("excuse") } returns excuseMapping
        every { event.getOption("action") } returns actionMapping
        every { event.getOption("author") } returns authorMapping
        every { actionMapping.asString } returns null.toString()
        every { authorMapping.asMember } returns null
        every { event.options } returns listOf(excuseMapping)
        every { excuseMapping.asString } returns "Excuse 1"
        every { excuseService.listAllGuildExcuses(1L) } returns excuseDtos
        every { excuseService.createNewExcuse(any()) } returns excuseToCreate

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify {
            excuseService.listAllGuildExcuses(1L)
            event.hook.sendMessage("I've heard that one before, keep up.")
        }
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
    }

    @Test
    fun approvePendingExcuse_asSuperUser() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val userDto = mockk<database.dto.UserDto>()
        val deleteDelay = 0
        val preUpdatedExcuse = ExcuseDto(1, 1L, "UserName", "Excuse 1", false)
        val excuseToBeReturnedByUpdate = ExcuseDto(1, 1L, "UserName", "Excuse 1", true)
        val excuseMapping = mockk<OptionMapping>()
        val actionMapping = mockk<OptionMapping>()
        every { event.getOption("id") } returns excuseMapping
        every { event.getOption("action") }.returns(actionMapping)
        every { event.options } returns listOf(excuseMapping)
        every { userDto.superUser } returns true
        every { excuseMapping.asInt } returns 1
        every { excuseMapping.asLong } returns 1
        every { actionMapping.asString } returns "approve"
        every { excuseService.getExcuseById(1) } returns preUpdatedExcuse
        every { excuseService.updateExcuse(any()) } returns excuseToBeReturnedByUpdate

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify {
            excuseService.getExcuseById(excuseToBeReturnedByUpdate.id)
            excuseService.updateExcuse(any())
            event.hook.sendMessageFormat(any(), excuseToBeReturnedByUpdate.excuse)
        }
    }

    @Test
    fun approvePendingExcuse_asNonAuthorisedUser() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val deleteDelay = 0
        val excuseToCreate = ExcuseDto(1, 1L, "UserName", "Excuse 1", true)
        val excuseMapping = mockk<OptionMapping>()
        val actionMapping = mockk<OptionMapping>()
        every { event.getOption("id") } returns excuseMapping
        every { event.getOption("action") } returns actionMapping
        every { event.options } returns listOf(excuseMapping)
        every { excuseMapping.asInt } returns 1
        every { actionMapping.asString } returns "approve"
        every { excuseService.getExcuseById(1) } returns excuseToCreate
        every { excuseService.updateExcuse(any()) } returns excuseToCreate
        every { CommandTest.guild.owner } returns CommandTest.member
        every { CommandTest.member.effectiveName } returns "Effective Name"
        every { requestingUserDto.superUser } returns false

        // Act
        excuseCommand.handle(ctx, requestingUserDto, deleteDelay)

        // Assert
        verify {
            event.hook.sendMessageFormat("You do not have adequate permissions to use this command, if you believe this is a mistake talk to Effective Name")
        }
    }

    @Test
    fun listAllPendingExcuses_WithValidPendingOnes() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val userDto = mockk<database.dto.UserDto>()
        val deleteDelay = 0

        // Mock the behavior of the excuseService when listing approved guild excuses
        val excuseDtos: List<ExcuseDto?> = listOf(
            ExcuseDto(1, 1L, "TestAuthor", "Excuse 1", true),
            ExcuseDto(2, 1L, "TestAuthor", "Excuse 2", true),
            ExcuseDto(3, 1L, "TestAuthor", "Excuse 3", true)
        )
        val optionMapping = mockk<OptionMapping>()
        every { event.options } returns listOf(optionMapping)
        every { event.getOption("action") } returns optionMapping
        every { optionMapping.asString } returns "pending"
        every { excuseService.listPendingGuildExcuses(any()) } returns excuseDtos

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun listAllPendingExcuses_WithNoValidPendingOnes() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val userDto = mockk<database.dto.UserDto>()
        val deleteDelay = 0

        // Mock the behavior of the excuseService when listing approved guild excuses
        val optionMapping = mockk<OptionMapping>()
        every { event.options } returns listOf(optionMapping)
        every { event.getOption("action") } returns optionMapping
        every { optionMapping.asString } returns "pending"
        every { excuseService.listPendingGuildExcuses(any()) } returns emptyList<ExcuseDto>()

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify { event.hook.sendMessage("There are no excuses pending approval, consider submitting some.") }
    }

    @Test
    fun deleteExcuse_asValidUser() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val userDto = mockk<database.dto.UserDto>()
        val deleteDelay = 0
        val excuseToBeReturnedByUpdate = ExcuseDto(1, 1L, "UserName", "Excuse 1", true)
        val excuseMapping = mockk<OptionMapping>()
        val actionMapping = mockk<OptionMapping>()
        every { event.getOption("id") } returns excuseMapping
        every { event.getOption("action") } returns actionMapping
        every { event.options } returns listOf(excuseMapping)
        every { userDto.superUser } returns true
        every { excuseMapping.asLong } returns 1
        every { actionMapping.asString } returns "delete"
        every { excuseService.deleteExcuseById(1L) } just Runs

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify {
            excuseService.deleteExcuseById(any())
            event.hook.sendMessageFormat(any(), excuseToBeReturnedByUpdate.id)
        }
    }

    @Test
    fun deleteExcuse_asInvalidUser() {
        // Arrange
        val ctx = DefaultCommandContext(event)
        val userDto = mockk<database.dto.UserDto>()
        val deleteDelay = 0
        val preUpdatedExcuse = ExcuseDto(1, 1L, "UserName", "Excuse 1", false)
        val excuseToBeReturnedByUpdate = ExcuseDto(1, 1L, "UserName", "Excuse 1", true)
        val excuseMapping = mockk<OptionMapping>()
        val actionMapping = mockk<OptionMapping>()
        every { event.getOption("id") } returns excuseMapping
        every { event.getOption("action") } returns actionMapping
        every { event.options } returns listOf(excuseMapping)
        every { userDto.superUser } returns false
        every { excuseMapping.asInt } returns 1
        every { actionMapping.asString } returns "delete"
        every { CommandTest.guild.owner } returns CommandTest.member
        every { CommandTest.member.effectiveName } returns "Effective Name"
        every { excuseService.getExcuseById(1) } returns preUpdatedExcuse
        every { excuseService.updateExcuse(any()) } returns excuseToBeReturnedByUpdate

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        verify {
            event.hook.sendMessageFormat("You do not have adequate permissions to use this command, if you believe this is a mistake talk to Effective Name")
        }
        verify(exactly = 0) { excuseService.deleteExcuseById(1) }
    }
}