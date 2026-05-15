package bot.toby.command.commands.misc

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.DefaultCommandContext
import database.dto.ExcuseDto
import database.service.ExcuseService
import database.service.PagedExcuses
import io.mockk.*
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class ExcuseCommandTest : CommandTest {
    private lateinit var excuseCommand: ExcuseCommand
    private lateinit var excuseService: ExcuseService

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        excuseService = mockk(relaxed = true)
        excuseCommand = ExcuseCommand(excuseService)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
    }

    // /excuse random

    @Test
    fun `random returns approved excuse when there is at least one`() {
        val ctx = DefaultCommandContext(event)
        val approved = ExcuseDto(id = 1L, guildId = 1L, author = "Alice", excuse = "the dog ate it", approved = true)
        every { event.subcommandName } returns ExcuseCommand.RANDOM
        every { excuseService.listApprovedGuildExcuses(1L) } returns listOf(approved)

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify {
            event.hook.sendMessage("Excuse #1: 'the dog ate it' - Alice.")
        }
    }

    @Test
    fun `random falls back to message when there are no approved excuses`() {
        val ctx = DefaultCommandContext(event)
        every { event.subcommandName } returns ExcuseCommand.RANDOM
        every { excuseService.listApprovedGuildExcuses(1L) } returns emptyList()

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify { event.hook.sendMessage("There are no approved excuses, consider submitting some.") }
    }

    @Test
    fun `null subcommand defaults to random for muscle-memory`() {
        val ctx = DefaultCommandContext(event)
        val approved = ExcuseDto(id = 7L, guildId = 1L, author = "Bob", excuse = "traffic was bad", approved = true)
        every { event.subcommandName } returns null
        every { excuseService.listApprovedGuildExcuses(1L) } returns listOf(approved)

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify { event.hook.sendMessage("Excuse #7: 'traffic was bad' - Bob.") }
    }

    // /excuse submit

    @Test
    fun `submit creates a new pending excuse with author from user`() {
        val ctx = DefaultCommandContext(event)
        val saved = ExcuseDto(id = 10L, guildId = 1L, author = "UserName", excuse = "I forgot", approved = false)
        val textOption = mockk<OptionMapping> { every { asString } returns "I forgot" }
        every { event.subcommandName } returns ExcuseCommand.SUBMIT
        every { event.getOption("text") } returns textOption
        every { event.getOption("author") } returns null
        every { excuseService.listAllGuildExcuses(1L) } returns emptyList()
        every { excuseService.createNewExcuse(any()) } returns saved

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify {
            excuseService.createNewExcuse(withArg<ExcuseDto> {
                assert(it.guildId == 1L)
                assert(it.excuse == "I forgot")
                assert(it.authorDiscordId == 1L) // from event.user.idLong in CommandTest
                assert(!it.approved)
            })
            event.hook.sendMessage("Submitted excuse 'I forgot' - UserName with id '10' for approval.")
        }
    }

    @Test
    fun `submit rejects duplicates case-insensitively`() {
        val ctx = DefaultCommandContext(event)
        val existing = ExcuseDto(id = 1L, guildId = 1L, author = "Bob", excuse = "I FORGOT", approved = true)
        val textOption = mockk<OptionMapping> { every { asString } returns "i forgot" }
        every { event.subcommandName } returns ExcuseCommand.SUBMIT
        every { event.getOption("text") } returns textOption
        every { event.getOption("author") } returns null
        every { excuseService.listAllGuildExcuses(1L) } returns listOf(existing)

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify { event.hook.sendMessage(ExcuseCommand.EXISTING_EXCUSE_MESSAGE) }
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
    }

    @Test
    fun `submit blank text complains and does not create`() {
        val ctx = DefaultCommandContext(event)
        val textOption = mockk<OptionMapping> { every { asString } returns "   " }
        every { event.subcommandName } returns ExcuseCommand.SUBMIT
        every { event.getOption("text") } returns textOption
        every { event.getOption("author") } returns null

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify { event.hook.sendMessage("Provide some excuse text.") }
        verify(exactly = 0) { excuseService.createNewExcuse(any()) }
    }

    // /excuse approve

    @Test
    fun `approve as superuser flips approval and replies with text`() {
        val ctx = DefaultCommandContext(event)
        val pending = ExcuseDto(id = 5L, guildId = 1L, author = "Alice", excuse = "rain delay", approved = false)
        val approved = ExcuseDto(id = 5L, guildId = 1L, author = "Alice", excuse = "rain delay", approved = true)
        val idOption = mockk<OptionMapping> { every { asLong } returns 5L }
        every { event.subcommandName } returns ExcuseCommand.APPROVE
        every { event.getOption("id") } returns idOption
        every { requestingUserDto.superUser } returns true
        every { excuseService.getExcuseById(5L) } returns pending
        every { excuseService.approveExcuse(5L) } returns approved

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify {
            excuseService.approveExcuse(5L)
            event.hook.sendMessage("Approved excuse 'rain delay'.")
        }
    }

    @Test
    fun `approve as non-superuser is denied`() {
        val ctx = DefaultCommandContext(event)
        val idOption = mockk<OptionMapping> { every { asLong } returns 5L }
        every { event.subcommandName } returns ExcuseCommand.APPROVE
        every { event.getOption("id") } returns idOption
        every { requestingUserDto.superUser } returns false
        every { CommandTest.guild.owner } returns CommandTest.member
        every { CommandTest.member.effectiveName } returns "OwnerName"

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify {
            event.hook.sendMessageFormat(
                "You do not have adequate permissions to use this command, if you believe this is a mistake talk to OwnerName"
            )
        }
        verify(exactly = 0) { excuseService.approveExcuse(any()) }
    }

    @Test
    fun `approve of already-approved row returns the heard-it-before message`() {
        val ctx = DefaultCommandContext(event)
        val alreadyApproved = ExcuseDto(id = 5L, guildId = 1L, author = "Alice", excuse = "rain delay", approved = true)
        val idOption = mockk<OptionMapping> { every { asLong } returns 5L }
        every { event.subcommandName } returns ExcuseCommand.APPROVE
        every { event.getOption("id") } returns idOption
        every { requestingUserDto.superUser } returns true
        every { excuseService.getExcuseById(5L) } returns alreadyApproved

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify { event.hook.sendMessage(ExcuseCommand.EXISTING_EXCUSE_MESSAGE) }
        verify(exactly = 0) { excuseService.approveExcuse(any()) }
    }

    // /excuse delete

    @Test
    fun `delete as superuser succeeds`() {
        val ctx = DefaultCommandContext(event)
        val idOption = mockk<OptionMapping> { every { asLong } returns 5L }
        every { event.subcommandName } returns ExcuseCommand.DELETE
        every { event.getOption("id") } returns idOption
        every { requestingUserDto.superUser } returns true
        every { excuseService.deleteExcuseById(5L) } just Runs

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify {
            excuseService.deleteExcuseById(5L)
            event.hook.sendMessage("Deleted excuse with id '5'.")
        }
    }

    @Test
    fun `delete as author of own pending excuse succeeds`() {
        val ctx = DefaultCommandContext(event)
        val idOption = mockk<OptionMapping> { every { asLong } returns 5L }
        every { event.subcommandName } returns ExcuseCommand.DELETE
        every { event.getOption("id") } returns idOption
        every { requestingUserDto.superUser } returns false
        every { excuseService.canRequesterDeleteOwnPending(5L, 1L) } returns true

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify {
            excuseService.deleteExcuseById(5L)
            event.hook.sendMessage("Deleted excuse with id '5'.")
        }
    }

    @Test
    fun `delete by random user is denied`() {
        val ctx = DefaultCommandContext(event)
        val idOption = mockk<OptionMapping> { every { asLong } returns 5L }
        every { event.subcommandName } returns ExcuseCommand.DELETE
        every { event.getOption("id") } returns idOption
        every { requestingUserDto.superUser } returns false
        every { excuseService.canRequesterDeleteOwnPending(5L, 1L) } returns false
        every { CommandTest.guild.owner } returns CommandTest.member
        every { CommandTest.member.effectiveName } returns "OwnerName"

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify {
            event.hook.sendMessageFormat(
                "You do not have adequate permissions to use this command, if you believe this is a mistake talk to OwnerName"
            )
        }
        verify(exactly = 0) { excuseService.deleteExcuseById(any()) }
    }

    // /excuse list

    @Test
    fun `list approved with no results sends empty message`() {
        val ctx = DefaultCommandContext(event)
        every { event.subcommandName } returns ExcuseCommand.LIST
        every { event.getOption("scope") } returns null
        every { event.getOption("page") } returns null
        every {
            excuseService.listApprovedPaged(1L, 1, ExcuseCommand.EXCUSES_PER_PAGE)
        } returns PagedExcuses(emptyList(), 1, ExcuseCommand.EXCUSES_PER_PAGE, 0L)

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify { event.hook.sendMessage("There are no approved excuses, consider submitting some.") }
    }

    @Test
    fun `list pending is denied for non-superusers`() {
        val ctx = DefaultCommandContext(event)
        val scopeOption = mockk<OptionMapping> { every { asString } returns ExcuseCommand.SCOPE_PENDING }
        every { event.subcommandName } returns ExcuseCommand.LIST
        every { event.getOption("scope") } returns scopeOption
        every { event.getOption("page") } returns null
        every { requestingUserDto.superUser } returns false
        every { CommandTest.guild.owner } returns CommandTest.member
        every { CommandTest.member.effectiveName } returns "OwnerName"

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify {
            event.hook.sendMessageFormat(
                "You do not have adequate permissions to use this command, if you believe this is a mistake talk to OwnerName"
            )
        }
        verify(exactly = 0) { excuseService.listPendingPaged(any(), any(), any()) }
    }

    @Test
    fun `list approved with rows sends an embed reply`() {
        val ctx = DefaultCommandContext(event)
        every { event.subcommandName } returns ExcuseCommand.LIST
        every { event.getOption("scope") } returns null
        every { event.getOption("page") } returns null
        every {
            excuseService.listApprovedPaged(1L, 1, ExcuseCommand.EXCUSES_PER_PAGE)
        } returns PagedExcuses(
            rows = listOf(
                ExcuseDto(id = 1L, guildId = 1L, author = "Alice", excuse = "rain", approved = true),
                ExcuseDto(id = 2L, guildId = 1L, author = "Bob", excuse = "snow", approved = true),
            ),
            page = 1,
            pageSize = ExcuseCommand.EXCUSES_PER_PAGE,
            totalCount = 2L,
        )

        excuseCommand.handle(ctx, requestingUserDto, 0)

        // 2 rows fit on one page so the embed-and-delete shortcut fires.
        verify { event.hook.sendMessageEmbeds(any<net.dv8tion.jda.api.entities.MessageEmbed>(), *anyVararg()) }
    }

    // /excuse search

    @Test
    fun `search with empty query complains`() {
        val ctx = DefaultCommandContext(event)
        val queryOption = mockk<OptionMapping> { every { asString } returns "   " }
        every { event.subcommandName } returns ExcuseCommand.SEARCH
        every { event.getOption("query") } returns queryOption
        every { event.getOption("page") } returns null

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify { event.hook.sendMessage("Provide a search query.") }
        verify(exactly = 0) { excuseService.searchApproved(any(), any(), any(), any()) }
    }

    @Test
    fun `search empty results returns query-aware message`() {
        val ctx = DefaultCommandContext(event)
        val queryOption = mockk<OptionMapping> { every { asString } returns "alpaca" }
        every { event.subcommandName } returns ExcuseCommand.SEARCH
        every { event.getOption("query") } returns queryOption
        every { event.getOption("page") } returns null
        every {
            excuseService.searchApproved(1L, "alpaca", 1, ExcuseCommand.EXCUSES_PER_PAGE)
        } returns PagedExcuses(emptyList(), 1, ExcuseCommand.EXCUSES_PER_PAGE, 0L)

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify { event.hook.sendMessage("No approved excuses match 'alpaca'.") }
    }

    // round-trip on the page-button id encoder/decoder

    @Test
    fun `encodePageButton round-trips through decodePageButton`() {
        val encoded = ExcuseCommand.encodePageButton(
            scope = ExcuseCommand.SCOPE_SEARCH,
            guildId = 42L,
            page = 3,
            query = "tricky:value with spaces",
        )
        val decoded = ExcuseCommand.decodePageButton(encoded)!!
        assert(decoded.scope == ExcuseCommand.SCOPE_SEARCH)
        assert(decoded.guildId == 42L)
        assert(decoded.page == 3)
        assert(decoded.query == "tricky:value with spaces")
    }

    @Test
    fun `decodePageButton returns null for unrelated component ids`() {
        assert(ExcuseCommand.decodePageButton("init:next") == null)
        assert(ExcuseCommand.decodePageButton("excuse-page:noop") == null)
        assert(ExcuseCommand.decodePageButton("excuse-page:approved:notanumber:1:") == null)
    }

    // resolveDisplayAuthor

    @Test
    fun `resolveDisplayAuthor returns current member effective name when member exists`() {
        val jda = mockk<net.dv8tion.jda.api.JDA>()
        val guild = mockk<net.dv8tion.jda.api.entities.Guild>()
        val member = mockk<net.dv8tion.jda.api.entities.Member>()
        every { jda.getGuildById(1L) } returns guild
        every { guild.getMemberById(42L) } returns member
        every { member.effectiveName } returns "CurrentNick"

        val row = ExcuseDto(id = 1L, guildId = 1L, author = "OldSnapshot", authorDiscordId = 42L)

        val resolved = ExcuseCommand.resolveDisplayAuthor(jda, 1L, row)

        assert(resolved == "CurrentNick") { "expected CurrentNick, got '$resolved'" }
    }

    @Test
    fun `resolveDisplayAuthor falls back to user name when member has left the guild`() {
        val jda = mockk<net.dv8tion.jda.api.JDA>()
        val guild = mockk<net.dv8tion.jda.api.entities.Guild>()
        val user = mockk<net.dv8tion.jda.api.entities.User>()
        every { jda.getGuildById(1L) } returns guild
        every { guild.getMemberById(42L) } returns null
        every { jda.getUserById(42L) } returns user
        every { user.name } returns "GlobalName"

        val row = ExcuseDto(id = 1L, guildId = 1L, author = "OldSnapshot", authorDiscordId = 42L)

        val resolved = ExcuseCommand.resolveDisplayAuthor(jda, 1L, row)

        assert(resolved == "GlobalName") { "expected GlobalName, got '$resolved'" }
    }

    @Test
    fun `resolveDisplayAuthor falls back to snapshot when both JDA lookups miss`() {
        val jda = mockk<net.dv8tion.jda.api.JDA>()
        every { jda.getGuildById(1L) } returns null
        every { jda.getUserById(42L) } returns null

        val row = ExcuseDto(id = 1L, guildId = 1L, author = "Legacy", authorDiscordId = 42L)

        val resolved = ExcuseCommand.resolveDisplayAuthor(jda, 1L, row)

        assert(resolved == "Legacy") { "expected Legacy, got '$resolved'" }
    }

    @Test
    fun `resolveDisplayAuthor uses snapshot directly for legacy rows without authorDiscordId`() {
        val jda = mockk<net.dv8tion.jda.api.JDA>()
        // No JDA stubs needed — the resolver shouldn't touch JDA when authorDiscordId is null.

        val row = ExcuseDto(id = 1L, guildId = 1L, author = "Legacy", authorDiscordId = null)

        val resolved = ExcuseCommand.resolveDisplayAuthor(jda, 1L, row)

        assert(resolved == "Legacy") { "expected Legacy, got '$resolved'" }
    }

    @Test
    fun `resolveDisplayAuthor returns Unknown when everything is null`() {
        val jda = mockk<net.dv8tion.jda.api.JDA>()
        every { jda.getGuildById(any<Long>()) } returns null
        every { jda.getUserById(any<Long>()) } returns null

        val row = ExcuseDto(id = 1L, guildId = 1L, author = null, authorDiscordId = 42L)

        val resolved = ExcuseCommand.resolveDisplayAuthor(jda, 1L, row)

        assert(resolved == "Unknown") { "expected Unknown, got '$resolved'" }
    }

    @Test
    fun `random uses current member name when authorDiscordId resolves`() {
        val ctx = DefaultCommandContext(event)
        val approved = ExcuseDto(
            id = 5L,
            guildId = 1L,
            author = "OldSnapshot",
            excuse = "the cat sat on it",
            approved = true,
            authorDiscordId = 99L,
        )
        every { event.subcommandName } returns ExcuseCommand.RANDOM
        every { excuseService.listApprovedGuildExcuses(1L) } returns listOf(approved)

        // Wire the JDA chain so resolveDisplayAuthor returns "NewNick".
        val freshMember = mockk<net.dv8tion.jda.api.entities.Member>()
        every { freshMember.effectiveName } returns "NewNick"
        every { CommandTest.guild.getMemberById(99L) } returns freshMember
        every { event.jda } returns CommandTest.jda
        every { CommandTest.jda.getGuildById(1L) } returns CommandTest.guild

        excuseCommand.handle(ctx, requestingUserDto, 0)

        verify { event.hook.sendMessage("Excuse #5: 'the cat sat on it' - NewNick.") }
    }
}
