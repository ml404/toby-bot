package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import database.dto.BrotherDto
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
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

    private fun userOption(id: Long, name: String): OptionMapping {
        val u = mockk<User>(relaxed = true) {
            every { idLong } returns id
            every { effectiveName } returns name
        }
        return mockk { every { asUser } returns u }
    }

    private fun stringOption(value: String): OptionMapping =
        mockk { every { asString } returns value }

    // ---- check ----

    @Test
    fun `check returns brother message when caller is registered`() {
        every { event.subcommandName } returns BrotherCommand.CHECK
        every { event.getOption("user") } returns null
        every { brotherService.getBrotherById(1L) } returns BrotherDto(1L, "TestBrother")

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify { event.hook.sendMessage("Of course, TestBrother is one of my brothers.") }
    }

    @Test
    fun `check returns negative message when caller is not registered`() {
        every { event.subcommandName } returns BrotherCommand.CHECK
        every { event.getOption("user") } returns null
        every { brotherService.getBrotherById(any()) } returns null

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify { event.hook.sendMessage("Effective Name is not registered as a brother.") }
    }

    @Test
    fun `check uses mentioned user when one is provided`() {
        every { event.subcommandName } returns BrotherCommand.CHECK
        every { event.getOption("user") } returns userOption(42L, "OtherUser")
        every { brotherService.getBrotherById(42L) } returns BrotherDto(42L, "MentionedBrother")

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { brotherService.getBrotherById(42L) }
        verify { event.hook.sendMessage("Of course, MentionedBrother is one of my brothers.") }
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

        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>(), *anyVararg()) }
    }

    @Test
    fun `list reports empty state when nobody is registered`() {
        every { event.subcommandName } returns BrotherCommand.LIST
        every { brotherService.listBrothers() } returns emptyList()

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify { event.hook.sendMessage("No brothers are registered yet.") }
    }

    // ---- add ----

    @Test
    fun `add creates a brother when caller is a superuser and entry is new`() {
        every { event.subcommandName } returns BrotherCommand.ADD
        every { event.getOption("user") } returns userOption(100L, "Newbie")
        every { event.getOption("name") } returns stringOption("Newbie")
        every { requestingUserDto.superUser } returns true
        every { brotherService.getBrotherById(100L) } returns null

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) {
            brotherService.createNewBrother(match { it.discordId == 100L && it.brotherName == "Newbie" })
        }
        verify { event.hook.sendMessage("Registered Newbie as 'Newbie'.") }
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
        every { event.subcommandName } returns BrotherCommand.ADD
        every { event.getOption("user") } returns userOption(100L, "Newbie")
        every { event.getOption("name") } returns stringOption("Newbie")
        every { requestingUserDto.superUser } returns true
        every { brotherService.getBrotherById(100L) } returns BrotherDto(100L, "Existing")

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { brotherService.createNewBrother(any()) }
        verify { event.hook.sendMessage("Newbie is already registered as 'Existing'.") }
    }

    // ---- remove ----

    @Test
    fun `remove deletes when caller is superuser and entry exists`() {
        every { event.subcommandName } returns BrotherCommand.REMOVE
        every { event.getOption("user") } returns userOption(100L, "Goner")
        every { requestingUserDto.superUser } returns true
        every { brotherService.getBrotherById(100L) } returns BrotherDto(100L, "Goner")

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { brotherService.deleteBrotherById(100L) }
        verify { event.hook.sendMessage("Unregistered Goner.") }
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
        every { event.subcommandName } returns BrotherCommand.REMOVE
        every { event.getOption("user") } returns userOption(100L, "NotHere")
        every { requestingUserDto.superUser } returns true
        every { brotherService.getBrotherById(100L) } returns null

        brotherCommand.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { brotherService.deleteBrotherById(any()) }
        verify { event.hook.sendMessage("NotHere isn't registered as a brother.") }
    }

    // ---- subcommand surface ----

    @Test
    fun `subCommands lists all four operations`() {
        val names = brotherCommand.subCommands.map { it.name }.toSet()
        assertEquals(setOf("check", "list", "add", "remove"), names)
    }
}
