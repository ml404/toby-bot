package toby.command.commands.misc

import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.kotlin.any
import toby.command.CommandContext
import toby.command.CommandTest
import toby.jpa.dto.ExcuseDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IExcuseService

class ExcuseCommandTest : CommandTest {
    private lateinit var excuseCommand: ExcuseCommand

    @Mock
    private lateinit var excuseService: IExcuseService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        excuseService = Mockito.mock(IExcuseService::class.java)
        excuseCommand = ExcuseCommand(excuseService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    @Test
    fun aRandomApprovedExcuse_WhenNoOptionsUsed(){
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0

        // Mock the behavior of the excuseService when listing approved guild excuses
        val excuseDto = ExcuseDto(1, 1L, "TestAuthor", "Excuse 1", true)
        val excuseDtos: List<ExcuseDto?> = listOf(
            excuseDto
        )
        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options)
            .thenReturn(emptyList())
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("action")).thenReturn(optionMapping)
        Mockito.`when`(optionMapping.asString).thenReturn("all")
        Mockito.`when`(excuseService.listApprovedGuildExcuses(Mockito.anyLong())).thenReturn(excuseDtos)

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            eq("Excuse #%d: '%s' - %s."),
            eq<Int?>(excuseDto.id),
            eq<String?>(excuseDto.excuse),
            eq<String?>(excuseDto.author)
        )
    }

    @Test
    fun listAllApprovedExcuses_WithValidApprovedOnes() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0

        // Mock the behavior of the excuseService when listing approved guild excuses
        val excuseDtos: List<ExcuseDto?> = listOf(
            ExcuseDto(1, 1L, "TestAuthor", "Excuse 1", true),
            ExcuseDto(2, 1L, "TestAuthor", "Excuse 2", true),
            ExcuseDto(3, 1L, "TestAuthor", "Excuse 3", true)
        )
        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(optionMapping))
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("action")).thenReturn(optionMapping)
        Mockito.`when`(optionMapping.asString).thenReturn("all")
        Mockito.`when`(excuseService.listApprovedGuildExcuses(Mockito.anyLong())).thenReturn(excuseDtos)

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.anyString())
    }

    @Test
    fun listAllApprovedExcuses_WithNoValidApprovedOnes() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0

        // Mock the behavior of the excuseService when listing approved guild excuses
        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options)
            .thenReturn(emptyList())
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("action")).thenReturn(optionMapping)
        Mockito.`when`(optionMapping.asString).thenReturn("all")
        Mockito.`when`(excuseService.listApprovedGuildExcuses(Mockito.anyLong())).thenReturn(emptyList<ExcuseDto>())

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(eq("There are no approved excuses, consider submitting some."))
    }

    @Test
    fun createNewExcuse() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0
        val excuseToCreate = ExcuseDto(1, 1L, "UserName", "Excuse 1", false)
        val excuseMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("excuse")).thenReturn(excuseMapping)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(excuseMapping))
        Mockito.`when`(excuseMapping.asString).thenReturn("Excuse 1")
        Mockito.`when`(excuseService.listAllGuildExcuses(1L)).thenReturn(emptyList<ExcuseDto>())
        Mockito.`when`(excuseService.createNewExcuse(any()))
            .thenReturn(excuseToCreate)

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        //see if excuse exists
        Mockito.verify(excuseService, Mockito.times(1)).listAllGuildExcuses(1L)
        // it doesn't, so create it
        Mockito.verify(excuseService, Mockito.times(1))
            .createNewExcuse(eq(ExcuseDto(null, 1L, "UserName", "Excuse 1", false)))
        // send a message that your excuse exists in pending form
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.anyString(),
            eq<String?>(excuseToCreate.excuse),
            eq<String?>(excuseToCreate.author),
            eq<Int?>(excuseToCreate.id)
        )
    }

    @Test
    fun createNewExcuse_thatExists_throwsError() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0

        val excuseToCreate = ExcuseDto(1, 1L, "UserName", "Excuse 1", false)
        val excuseDtos: List<ExcuseDto?> = listOf(
            excuseToCreate
        )
        val excuseMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("excuse")).thenReturn(excuseMapping)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(excuseMapping))
        Mockito.`when`(excuseMapping.asString).thenReturn("Excuse 1")
        Mockito.`when`(excuseService.listAllGuildExcuses(1L)).thenReturn(excuseDtos)
        Mockito.`when`(excuseService.createNewExcuse(any()))
            .thenReturn(excuseToCreate)

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        //see if excuse exists
        Mockito.verify(excuseService, Mockito.times(1)).listAllGuildExcuses(1L)
        // it does, so don't create it
        Mockito.verify(excuseService, Mockito.times(0))
            .createNewExcuse(eq(ExcuseDto(null, 1L, "UserName", "Excuse 1", false)))
        // send a message that your excuse exists in pending form
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(eq("I've heard that one before, keep up."))
    }

    @Test
    fun approvePendingExcuse_asSuperUser() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0
        val preUpdatedExcuse = ExcuseDto(1, 1L, "UserName", "Excuse 1", false)
        val excuseToBeReturnedByUpdate = ExcuseDto(1, 1L, "UserName", "Excuse 1", true)
        val excuseMapping = Mockito.mock(OptionMapping::class.java)
        val actionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("id")).thenReturn(excuseMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("action")).thenReturn(actionMapping)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(excuseMapping))
        Mockito.`when`(userDto.superUser).thenReturn(true)
        Mockito.`when`(excuseMapping.asInt).thenReturn(1)
        Mockito.`when`(actionMapping.asString).thenReturn("approve")
        Mockito.`when`(excuseService.getExcuseById(1)).thenReturn(preUpdatedExcuse)
        Mockito.`when`(excuseService.updateExcuse(any())).thenReturn(excuseToBeReturnedByUpdate)

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        //see if excuse exists
        Mockito.verify(excuseService, Mockito.times(1))
            .getExcuseById(eq(excuseToBeReturnedByUpdate.id))
        // it doesn't, so create it
        Mockito.verify(excuseService, Mockito.times(1)).updateExcuse(eq(ExcuseDto(1, 1L, "UserName", "Excuse 1", true)))
        // send a message that your excuse exists in pending form
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1)).sendMessageFormat(
            ArgumentMatchers.anyString(),
            eq<String?>(excuseToBeReturnedByUpdate.excuse)
        )
    }

    @Test
    fun approvePendingExcuse_asNonAuthorisedUser() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0
        val excuseToCreate = ExcuseDto(1, 1L, "UserName", "Excuse 1", true)
        val excuseMapping = Mockito.mock(OptionMapping::class.java)
        val actionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("id")).thenReturn(excuseMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("action")).thenReturn(actionMapping)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(excuseMapping))
        Mockito.`when`(excuseMapping.asInt).thenReturn(1)
        Mockito.`when`(actionMapping.asString).thenReturn("approve")
        Mockito.`when`(excuseService.getExcuseById(1)).thenReturn(excuseToCreate)
        Mockito.`when`(excuseService.updateExcuse(any()))
            .thenReturn(excuseToCreate)
        Mockito.`when`(CommandTest.guild.owner).thenReturn(CommandTest.member)
        Mockito.`when`(CommandTest.member.effectiveName).thenReturn("Effective Name")
        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        // send a message to say you're not authorised
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: Effective Name"))
        //don't do lookups
        Mockito.verify(excuseService, Mockito.times(0)).getExcuseById(eq(excuseToCreate.id))
        //don't approve
        Mockito.verify(excuseService, Mockito.times(0)).updateExcuse(eq(ExcuseDto(1, 1L, "UserName", "Excuse 1", true)))
    }


    @Test
    fun listAllPendingExcuses_WithValidPendingOnes() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0

        // Mock the behavior of the excuseService when listing approved guild excuses
        val excuseDtos: List<ExcuseDto?> = listOf(
            ExcuseDto(1, 1L, "TestAuthor", "Excuse 1", true),
            ExcuseDto(2, 1L, "TestAuthor", "Excuse 2", true),
            ExcuseDto(3, 1L, "TestAuthor", "Excuse 3", true)
        )
        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(optionMapping))
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("action")).thenReturn(optionMapping)
        Mockito.`when`(optionMapping.asString).thenReturn("pending")
        Mockito.`when`(excuseService.listPendingGuildExcuses(Mockito.anyLong())).thenReturn(excuseDtos)

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(ArgumentMatchers.anyString())
    }

    @Test
    fun listAllPendingExcuses_WithNoValidPendingOnes() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0

        // Mock the behavior of the excuseService when listing approved guild excuses
        val optionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(optionMapping))
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("action")).thenReturn(optionMapping)
        Mockito.`when`(optionMapping.asString).thenReturn("pending")
        Mockito.`when`(excuseService.listPendingGuildExcuses(Mockito.anyLong())).thenReturn(emptyList<ExcuseDto>())

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessage(eq("There are no excuses pending approval, consider submitting some."))
    }

    @Test
    fun deleteExcuse_asValidUser() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0
        val preUpdatedExcuse = ExcuseDto(1, 1L, "UserName", "Excuse 1", false)
        val excuseToBeReturnedByUpdate = ExcuseDto(1, 1L, "UserName", "Excuse 1", true)
        val excuseMapping = Mockito.mock(OptionMapping::class.java)
        val actionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("id")).thenReturn(excuseMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("action")).thenReturn(actionMapping)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(excuseMapping))
        Mockito.`when`(userDto.superUser).thenReturn(true)
        Mockito.`when`(excuseMapping.asInt).thenReturn(1)
        Mockito.`when`(actionMapping.asString).thenReturn("delete")
        Mockito.`when`(excuseService.getExcuseById(1)).thenReturn(preUpdatedExcuse)
        Mockito.`when`(excuseService.updateExcuse(any()))
            .thenReturn(excuseToBeReturnedByUpdate)

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        // deleteById
        Mockito.verify(excuseService, Mockito.times(1)).deleteExcuseById(eq(1))
        // post update about deleting entry
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(ArgumentMatchers.anyString(), eq<Int?>(excuseToBeReturnedByUpdate.id))
    }

    @Test
    fun deleteExcuse_asInvalidUser() {
        // Arrange
        val ctx = CommandContext(CommandTest.event)
        val userDto = Mockito.mock(UserDto::class.java)
        val deleteDelay = 0
        val preUpdatedExcuse = ExcuseDto(1, 1L, "UserName", "Excuse 1", false)
        val excuseToBeReturnedByUpdate = ExcuseDto(1, 1L, "UserName", "Excuse 1", true)
        val excuseMapping = Mockito.mock(OptionMapping::class.java)
        val actionMapping = Mockito.mock(OptionMapping::class.java)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("id")).thenReturn(excuseMapping)
        Mockito.`when`<OptionMapping>(CommandTest.event.getOption("action")).thenReturn(actionMapping)
        Mockito.`when`<List<OptionMapping>>(CommandTest.event.options).thenReturn(listOf(excuseMapping))
        Mockito.`when`(userDto.superUser).thenReturn(false)
        Mockito.`when`(excuseMapping.asInt).thenReturn(1)
        Mockito.`when`(actionMapping.asString).thenReturn("delete")
        Mockito.`when`(CommandTest.guild.owner).thenReturn(CommandTest.member)
        Mockito.`when`(CommandTest.member.effectiveName).thenReturn("Effective Name")
        Mockito.`when`(excuseService.getExcuseById(1)).thenReturn(preUpdatedExcuse)
        Mockito.`when`(excuseService.updateExcuse(any()))
            .thenReturn(excuseToBeReturnedByUpdate)

        // Act
        excuseCommand.handle(ctx, userDto, deleteDelay)

        // Assert
        // deleteById
        Mockito.verify(excuseService, Mockito.times(0)).deleteExcuseById(eq(1))
        // post error message
        Mockito.verify(CommandTest.interactionHook, Mockito.times(1))
            .sendMessageFormat(eq("You do not have adequate permissions to use this command, if you believe this is a mistake talk to the server owner: Effective Name"))
    }

    @Test
    fun testHandle_InvalidAction() {
        // Test for handling an invalid action case
    }
}