package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import bot.toby.helpers.UserDtoHelper
import database.dto.UserDto
import database.poker.PokerTable
import database.poker.PokerTableRegistry
import database.service.PokerService
import database.service.PokerService.BuyInOutcome
import database.service.PokerService.CashOutOutcome
import database.service.PokerService.CreateOutcome
import database.service.PokerService.RebuyOutcome
import database.service.PokerService.StartHandOutcome
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PokerCommandTest : CommandTest {

    private lateinit var pokerService: PokerService
    private lateinit var tableRegistry: PokerTableRegistry
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var command: PokerCommand

    private val hostId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        pokerService = mockk(relaxed = true)
        tableRegistry = mockk(relaxed = true)
        userDtoHelper = mockk(relaxed = true)
        command = PokerCommand(pokerService, tableRegistry, userDtoHelper)
        every { guild.idLong } returns guildId
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun intOpt(value: Long): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asLong } returns value
        return o
    }

    private fun userDto() = UserDto(discordId = hostId, guildId = guildId).apply { socialCredit = 1000L }

    private fun stubTable(tableId: Long): PokerTable = PokerTable(
        id = tableId,
        guildId = guildId,
        hostDiscordId = hostId,
        minBuyIn = 100L,
        maxBuyIn = 5000L,
        smallBlind = 5L,
        bigBlind = 10L,
        smallBet = 10L,
        bigBet = 20L,
        maxRaisesPerStreet = 4,
        maxSeats = 6,
    )

    @Test
    fun `create subcommand happy path delegates to PokerService and posts lobby embed`() {
        every { event.subcommandName } returns "create"
        every { event.getOption("chips") } returns intOpt(200L)
        every { pokerService.createTable(hostId, guildId, 200L) } returns CreateOutcome.Ok(tableId = 7L)
        every { tableRegistry.get(7L) } returns stubTable(7L)

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 1) { pokerService.createTable(hostId, guildId, 200L) }
        verify(exactly = 1) { tableRegistry.get(7L) }
    }

    @Test
    fun `create with insufficient credits surfaces error and does not look up table`() {
        every { event.subcommandName } returns "create"
        every { event.getOption("chips") } returns intOpt(200L)
        every { pokerService.createTable(hostId, guildId, 200L) } returns
            CreateOutcome.InsufficientCredits(have = 50L, needed = 200L)

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 0) { tableRegistry.get(any()) }
    }

    @Test
    fun `join subcommand delegates to PokerService buyIn`() {
        every { event.subcommandName } returns "join"
        every { event.getOption("table") } returns intOpt(7L)
        every { event.getOption("chips") } returns intOpt(300L)
        every { pokerService.buyIn(hostId, guildId, 7L, 300L) } returns
            BuyInOutcome.Ok(seatIndex = 1, newBalance = 700L)
        every { tableRegistry.get(7L) } returns stubTable(7L)

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 1) { pokerService.buyIn(hostId, guildId, 7L, 300L) }
    }

    @Test
    fun `join with already-seated does not look up table`() {
        every { event.subcommandName } returns "join"
        every { event.getOption("table") } returns intOpt(7L)
        every { event.getOption("chips") } returns intOpt(300L)
        every { pokerService.buyIn(hostId, guildId, 7L, 300L) } returns BuyInOutcome.AlreadySeated

        command.handle(DefaultCommandContext(event), userDto(), 0)

        // Failure branch: no further lookups.
        verify(exactly = 0) { tableRegistry.get(any()) }
    }

    @Test
    fun `start subcommand delegates to PokerService startHand and looks up table`() {
        every { event.subcommandName } returns "start"
        every { event.getOption("table") } returns intOpt(7L)
        every { pokerService.startHand(hostId, guildId, 7L, any()) } returns
            StartHandOutcome.Ok(handNumber = 1L)
        every { tableRegistry.get(7L) } returns stubTable(7L)

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 1) { pokerService.startHand(hostId, guildId, 7L, any()) }
    }

    @Test
    fun `start by non-host does not call tableRegistry get`() {
        every { event.subcommandName } returns "start"
        every { event.getOption("table") } returns intOpt(7L)
        every { pokerService.startHand(hostId, guildId, 7L, any()) } returns StartHandOutcome.NotHost

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 0) { tableRegistry.get(any()) }
    }

    @Test
    fun `leave subcommand delegates to PokerService cashOut`() {
        every { event.subcommandName } returns "leave"
        every { event.getOption("table") } returns intOpt(7L)
        every { pokerService.cashOut(hostId, guildId, 7L) } returns
            CashOutOutcome.Ok(chipsReturned = 200L, newBalance = 1200L)

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 1) { pokerService.cashOut(hostId, guildId, 7L) }
    }

    @Test
    fun `leave subcommand surfaces queued-for-end-of-hand outcome`() {
        every { event.subcommandName } returns "leave"
        every { event.getOption("table") } returns intOpt(7L)
        every { pokerService.cashOut(hostId, guildId, 7L) } returns
            CashOutOutcome.QueuedForEndOfHand(chipsHeld = 480L)

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 1) { pokerService.cashOut(hostId, guildId, 7L) }
    }

    @Test
    fun `tables subcommand renders registry list`() {
        every { event.subcommandName } returns "tables"
        every { tableRegistry.listForGuild(guildId) } returns listOf(stubTable(7L), stubTable(8L))

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 1) { tableRegistry.listForGuild(guildId) }
    }

    @Test
    fun `peek subcommand uses table registry but never calls service`() {
        every { event.subcommandName } returns "peek"
        every { event.getOption("table") } returns intOpt(7L)
        val table = stubTable(7L).apply {
            seats.add(PokerTable.Seat(discordId = hostId, chips = 1000L, holeCards = emptyList()))
        }
        every { tableRegistry.get(7L) } returns table

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 0) { pokerService.applyAction(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `rebuy subcommand delegates to PokerService rebuy`() {
        every { event.subcommandName } returns "rebuy"
        every { event.getOption("table") } returns intOpt(7L)
        every { event.getOption("chips") } returns intOpt(300L)
        every { pokerService.rebuy(hostId, guildId, 7L, 300L) } returns
            RebuyOutcome.Ok(seatChips = 500L, newBalance = 700L)

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 1) { pokerService.rebuy(hostId, guildId, 7L, 300L) }
        // Happy path renders an info embed; no need to look up the table.
        verify(exactly = 0) { tableRegistry.get(any()) }
    }

    @Test
    fun `rebuy with stack-cap rejection still surfaces an embed without table lookup`() {
        every { event.subcommandName } returns "rebuy"
        every { event.getOption("table") } returns intOpt(7L)
        every { event.getOption("chips") } returns intOpt(2000L)
        every { pokerService.rebuy(hostId, guildId, 7L, 2000L) } returns
            RebuyOutcome.StackCapped(cap = 5000L, current = 4500L)

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 0) { tableRegistry.get(any()) }
    }

    @Test
    fun `history without table option queries guild-wide`() {
        every { event.subcommandName } returns "history"
        every { event.getOption("table") } returns null
        every { event.getOption("limit") } returns null
        every { pokerService.recentHandsForGuild(guildId, PokerService.HISTORY_DEFAULT_LIMIT) } returns emptyList()

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 1) { pokerService.recentHandsForGuild(guildId, PokerService.HISTORY_DEFAULT_LIMIT) }
        verify(exactly = 0) { pokerService.recentHandsForTable(any(), any(), any()) }
    }

    @Test
    fun `history with table option scopes the lookup to that table`() {
        every { event.subcommandName } returns "history"
        every { event.getOption("table") } returns intOpt(7L)
        every { event.getOption("limit") } returns intOpt(5L)
        every { pokerService.recentHandsForTable(guildId, 7L, 5) } returns emptyList()

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 1) { pokerService.recentHandsForTable(guildId, 7L, 5) }
        verify(exactly = 0) { pokerService.recentHandsForGuild(any(), any()) }
    }

    @Test
    fun `unknown subcommand returns error without dispatching`() {
        every { event.subcommandName } returns "wibble"

        command.handle(DefaultCommandContext(event), userDto(), 0)

        verify(exactly = 0) { pokerService.createTable(any(), any(), any()) }
        verify(exactly = 0) { pokerService.buyIn(any(), any(), any(), any()) }
        verify(exactly = 0) { pokerService.startHand(any(), any(), any(), any()) }
    }
}
