package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.interactionHook
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.user
import bot.toby.command.DefaultCommandContext
import database.dto.BrotherDto
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BrotherCommandTest : CommandTest {
    private lateinit var brotherService: database.service.BrotherService
    private lateinit var brotherCommand: BrotherCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        brotherService = mockk(relaxed = true)
        brotherCommand = BrotherCommand(brotherService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearMocks(brotherService)
    }

    // ---- check ----

    @Test
    fun `check returns brother message when caller is registered`() {
        every { event.subcommandName } returns BrotherCommand.CHECK
        every { event.getOption("user") } returns null
        every { event.user.idLong } returns 1L
        every { brotherService.getBrotherById(1L) } returns BrotherDto(1L, "TestBrother")

        val replied = slot<String>()
        every { interactionHook.sendMessage(capture(replied)) } returns CommandTest.webhookMessageCreateAction

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { interactionHook.sendMessage(any<String>()) }
        assert(replied.captured.contains("TestBrother"))
    }

    @Test
    fun `check returns negative message when caller is not registered`() {
        every { event.subcommandName } returns BrotherCommand.CHECK
        every { event.getOption("user") } returns null
        every { event.user.idLong } returns 2L
        every { brotherService.getBrotherById(2L) } returns null

        val replied = slot<String>()
        every { interactionHook.sendMessage(capture(replied)) } returns CommandTest.webhookMessageCreateAction

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { interactionHook.sendMessage(any<String>()) }
        assert(replied.captured.contains("not registered"))
    }

    @Test
    fun `check uses mentioned user when one is provided`() {
        val mentioned = mockk<User>(relaxed = true) {
            every { idLong } returns 42L
            every { effectiveName } returns "OtherUser"
        }
        val opt = mockk<OptionMapping>()
        every { opt.asUser } returns mentioned

        every { event.subcommandName } returns BrotherCommand.CHECK
        every { event.getOption("user") } returns opt
        every { brotherService.getBrotherById(42L) } returns BrotherDto(42L, "MentionedBrother")

        val replied = slot<String>()
        every { interactionHook.sendMessage(capture(replied)) } returns CommandTest.webhookMessageCreateAction

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { brotherService.getBrotherById(42L) }
        assert(replied.captured.contains("MentionedBrother"))
    }

    // ---- list ----

    @Test
    fun `list sends an embed when brothers exist`() {
        every { event.subcommandName } returns BrotherCommand.LIST
        every { brotherService.listBrothers() } returns listOf(
            BrotherDto(1L, "Alice"),
            BrotherDto(2L, "Bob"),
        )

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { interactionHook.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `list reports empty state when nobody is registered`() {
        every { event.subcommandName } returns BrotherCommand.LIST
        every { brotherService.listBrothers() } returns emptyList()

        val replied = slot<String>()
        every { interactionHook.sendMessage(capture(replied)) } returns CommandTest.webhookMessageCreateAction

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { interactionHook.sendMessage(any<String>()) }
        assertEquals("No brothers are registered yet.", replied.captured)
    }

    // ---- add ----

    @Test
    fun `add creates a brother when caller is a superuser and entry is new`() {
        val target = mockk<User>(relaxed = true) {
            every { idLong } returns 100L
            every { effectiveName } returns "Newbie"
        }
        val userOpt = mockk<OptionMapping> { every { asUser } returns target }
        val nameOpt = mockk<OptionMapping> { every { asString } returns "Newbie" }

        every { event.subcommandName } returns BrotherCommand.ADD
        every { event.getOption("user") } returns userOpt
        every { event.getOption("name") } returns nameOpt
        every { requestingUserDto.superUser } returns true
        every { brotherService.getBrotherById(100L) } returns null

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) {
            brotherService.createNewBrother(match { it.discordId == 100L && it.brotherName == "Newbie" })
        }
    }

    @Test
    fun `add refuses non-superusers`() {
        every { event.subcommandName } returns BrotherCommand.ADD
        every { requestingUserDto.superUser } returns false

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { brotherService.createNewBrother(any()) }
    }

    @Test
    fun `add rejects duplicates`() {
        val target = mockk<User>(relaxed = true) {
            every { idLong } returns 100L
            every { effectiveName } returns "Newbie"
        }
        val userOpt = mockk<OptionMapping> { every { asUser } returns target }
        val nameOpt = mockk<OptionMapping> { every { asString } returns "Newbie" }

        every { event.subcommandName } returns BrotherCommand.ADD
        every { event.getOption("user") } returns userOpt
        every { event.getOption("name") } returns nameOpt
        every { requestingUserDto.superUser } returns true
        every { brotherService.getBrotherById(100L) } returns BrotherDto(100L, "Existing")

        val replied = slot<String>()
        every { interactionHook.sendMessage(capture(replied)) } returns CommandTest.webhookMessageCreateAction

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { brotherService.createNewBrother(any()) }
        assert(replied.captured.contains("already registered"))
    }

    // ---- remove ----

    @Test
    fun `remove deletes when caller is superuser and entry exists`() {
        val target = mockk<User>(relaxed = true) {
            every { idLong } returns 100L
            every { effectiveName } returns "Goner"
        }
        val userOpt = mockk<OptionMapping> { every { asUser } returns target }

        every { event.subcommandName } returns BrotherCommand.REMOVE
        every { event.getOption("user") } returns userOpt
        every { requestingUserDto.superUser } returns true
        every { brotherService.getBrotherById(100L) } returns BrotherDto(100L, "Goner")

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { brotherService.deleteBrotherById(100L) }
    }

    @Test
    fun `remove refuses non-superusers`() {
        every { event.subcommandName } returns BrotherCommand.REMOVE
        every { requestingUserDto.superUser } returns false

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { brotherService.deleteBrotherById(any()) }
    }

    @Test
    fun `remove reports when entry doesn't exist`() {
        val target = mockk<User>(relaxed = true) {
            every { idLong } returns 100L
            every { effectiveName } returns "NotHere"
        }
        val userOpt = mockk<OptionMapping> { every { asUser } returns target }

        every { event.subcommandName } returns BrotherCommand.REMOVE
        every { event.getOption("user") } returns userOpt
        every { requestingUserDto.superUser } returns true
        every { brotherService.getBrotherById(100L) } returns null

        val replied = slot<String>()
        every { interactionHook.sendMessage(capture(replied)) } returns CommandTest.webhookMessageCreateAction

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { brotherService.deleteBrotherById(any()) }
        assert(replied.captured.contains("isn't registered"))
    }

    // ---- subcommand surface ----

    @Test
    fun `subCommands lists all four operations`() {
        val names = brotherCommand.subCommands.map { it.name }.toSet()
        assertEquals(setOf("check", "list", "add", "remove"), names)
    }
}
