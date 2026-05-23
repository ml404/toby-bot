package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.requestingUserDto
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import bot.toby.helpers.UserDtoHelper
import database.connect4.Connect4SessionRegistry
import database.dto.UserDto
import database.service.Connect4Service
import database.service.PvpWagerService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class Connect4CommandTest : CommandTest {

    private lateinit var connect4Service: Connect4Service
    private lateinit var registry: Connect4SessionRegistry
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var command: Connect4Command

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        connect4Service = mockk(relaxed = true)
        registry = mockk(relaxed = true)
        userDtoHelper = mockk(relaxed = true)
        command = Connect4Command(connect4Service, registry, userDtoHelper)

        every { guild.idLong } returns 100L
        every { guild.id } returns "100"
        every { requestingUserDto.discordId } returns 1L

        every { webhookMessageCreateAction.addContent(any()) } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.addComponents(any<ActionRow>()) } returns webhookMessageCreateAction
        every { webhookMessageCreateAction.queue() } just runs
        every { userDtoHelper.calculateUserDto(any(), any()) } returns mockk<UserDto>(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkAll()
    }

    @Test
    fun `happy path posts the pending embed with accept and decline buttons`() {
        val opponent = stubOpponent(idLong = 2L, isBot = false)
        every { event.getOption("user") } returns opponent
        every { event.getOption("stake") } returns stakeOpt(50L)
        every { connect4Service.startMatch(1L, 2L, 100L, 50L) } returns
            PvpWagerService.StartOutcome.Ok(initiatorBalance = 500L)
        every { registry.register(100L, 1L, 2L, 50L, any(), any()) } returns Connect4SessionRegistry.Session(
            id = 7L, guildId = 100L, initiatorDiscordId = 1L,
            opponentDiscordId = 2L, stake = 50L, createdAt = java.time.Instant.now(),
        )

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { event.deferReply() }
        verify(exactly = 1) { connect4Service.startMatch(1L, 2L, 100L, 50L) }
        verify(exactly = 1) { registry.register(100L, 1L, 2L, 50L, any(), any()) }
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify(exactly = 1) { webhookMessageCreateAction.addContent(match<String> { it.contains("<@2>") }) }
        verify(exactly = 1) { webhookMessageCreateAction.addComponents(any<ActionRow>()) }
    }

    @Test
    fun `free-play match (no stake option) starts with stake 0`() {
        val opponent = stubOpponent(idLong = 2L, isBot = false)
        every { event.getOption("user") } returns opponent
        every { event.getOption("stake") } returns null
        every { connect4Service.startMatch(1L, 2L, 100L, 0L) } returns
            PvpWagerService.StartOutcome.Ok(initiatorBalance = 100L)
        every { registry.register(100L, 1L, 2L, 0L, any(), any()) } returns Connect4SessionRegistry.Session(
            id = 7L, guildId = 100L, initiatorDiscordId = 1L,
            opponentDiscordId = 2L, stake = 0L, createdAt = java.time.Instant.now(),
        )

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 1) { connect4Service.startMatch(1L, 2L, 100L, 0L) }
    }

    @Test
    fun `bot opponent is rejected`() {
        val botOpp = stubOpponent(idLong = 99L, isBot = true)
        every { event.getOption("user") } returns botOpp

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { connect4Service.startMatch(any(), any(), any(), any()) }
    }

    @Test
    fun `self-challenge is rejected before service is called`() {
        val selfOpp = stubOpponent(idLong = 1L, isBot = false)
        every { event.getOption("user") } returns selfOpp

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { connect4Service.startMatch(any(), any(), any(), any()) }
    }

    @Test
    fun `service-side preflight failure surfaces the start-error embed`() {
        val opponent = stubOpponent(idLong = 2L, isBot = false)
        every { event.getOption("user") } returns opponent
        every { event.getOption("stake") } returns stakeOpt(50L)
        every { connect4Service.startMatch(1L, 2L, 100L, 50L) } returns
            PvpWagerService.StartOutcome.InitiatorInsufficient(have = 10L, needed = 50L)

        command.handle(DefaultCommandContext(event), requestingUserDto, 0)

        verify(exactly = 0) { registry.register(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    private fun stubOpponent(idLong: Long, isBot: Boolean): OptionMapping {
        val user = mockk<User>(relaxed = true).also {
            every { it.idLong } returns idLong
            every { it.id } returns idLong.toString()
            every { it.isBot } returns isBot
            every { it.effectiveName } returns "Opponent-$idLong"
        }
        return mockk<OptionMapping>(relaxed = true).also { every { it.asUser } returns user }
    }

    private fun stakeOpt(value: Long): OptionMapping {
        return mockk<OptionMapping>(relaxed = true).also { every { it.asLong } returns value }
    }
}
