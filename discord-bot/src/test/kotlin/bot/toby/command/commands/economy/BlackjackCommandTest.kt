package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import database.blackjack.Blackjack
import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.dto.UserDto
import database.service.BlackjackService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

internal class BlackjackCommandTest : CommandTest {
    private lateinit var service: BlackjackService
    private lateinit var registry: BlackjackTableRegistry
    private lateinit var command: BlackjackCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        service = mockk(relaxed = true)
        registry = mockk(relaxed = true)
        command = BlackjackCommand(service, registry)
        every { guild.idLong } returns guildId
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun intOpt(value: Long): OptionMapping = mockk<OptionMapping>(relaxed = true).also {
        every { it.asLong } returns value
    }

    private fun soloTable(seatOwner: Long = discordId): BlackjackTable {
        val t = BlackjackTable(
            id = 11L,
            guildId = guildId,
            mode = BlackjackTable.Mode.SOLO,
            hostDiscordId = seatOwner,
            ante = 50L,
            maxSeats = 1
        )
        t.seats.add(BlackjackTable.Seat(discordId = seatOwner, ante = 50L, stake = 50L))
        return t
    }

    private fun multiTable(host: Long = discordId): BlackjackTable {
        val t = BlackjackTable(
            id = 22L,
            guildId = guildId,
            mode = BlackjackTable.Mode.MULTI,
            hostDiscordId = host,
            ante = 100L,
            maxSeats = 5
        )
        t.seats.add(BlackjackTable.Seat(discordId = host, ante = 100L, stake = 100L))
        return t
    }

    @Test
    fun `solo subcommand deals a hand and posts an embed with action buttons`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "solo"
        every { event.getOption("stake") } returns intOpt(50L)
        val table = soloTable()
        every { service.dealSolo(discordId, guildId, 50L) } returns
            BlackjackService.SoloDealOutcome.Dealt(tableId = table.id, snapshot = table, newBalance = 0L)
        every { registry.get(table.id) } returns table

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { service.dealSolo(discordId, guildId, 50L) }
        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify { webhookMessageCreateAction.addComponents(any<ActionRow>()) }
    }

    @Test
    fun `solo subcommand on natural blackjack short-circuits to a resolved embed`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "solo"
        every { event.getOption("stake") } returns intOpt(100L)
        val table = soloTable()
        val result = BlackjackTable.HandResult(
            handNumber = 1L,
            dealer = emptyList(),
            dealerTotal = 17,
            seatResults = mapOf(discordId to Blackjack.Result.PLAYER_BLACKJACK),
            payouts = mapOf(discordId to 250L),
            pot = 100L,
            rake = 0L,
            resolvedAt = Instant.now()
        )
        every { service.dealSolo(discordId, guildId, 100L) } returns
            BlackjackService.SoloDealOutcome.Resolved(table.id, result, 1_150L, 0L, 0L)
        every { registry.get(table.id) } returns table

        command.handle(DefaultCommandContext(event), user, 5)

        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify { service.closeSoloTable(table.id) }
    }

    @Test
    fun `solo subcommand without a stake never calls the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "solo"
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { service.dealSolo(any(), any(), any()) }
    }

    @Test
    fun `create subcommand seats the host and renders the lobby embed`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "create"
        every { event.getOption("ante") } returns intOpt(100L)
        val table = multiTable()
        every { service.createMultiTable(discordId, guildId, 100L) } returns
            BlackjackService.MultiCreateOutcome.Ok(tableId = table.id)
        every { registry.get(table.id) } returns table

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { service.createMultiTable(discordId, guildId, 100L) }
        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `start subcommand reports NotEnoughPlayers without crashing`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "start"
        every { event.getOption("table") } returns intOpt(22L)
        every { service.startMultiHand(discordId, guildId, 22L) } returns
            BlackjackService.MultiStartOutcome.NotEnoughPlayers

        command.handle(DefaultCommandContext(event), user, 5)

        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `tables subcommand lists registered MULTI tables only`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.subcommandName } returns "tables"
        every { registry.listForGuild(guildId) } returns listOf(multiTable(), soloTable())

        command.handle(DefaultCommandContext(event), user, 5)

        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify { registry.listForGuild(guildId) }
    }
}
