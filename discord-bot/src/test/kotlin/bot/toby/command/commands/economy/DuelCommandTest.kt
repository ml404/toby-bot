package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.duel.PendingDuelRegistry
import bot.toby.helpers.UserDtoHelper
import database.dto.UserDto
import database.service.DuelService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration

internal class DuelCommandTest : CommandTest {
    private lateinit var duelService: DuelService
    private lateinit var pendingDuelRegistry: PendingDuelRegistry
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var command: DuelCommand

    private val initiatorId = 1L
    private val opponentId = 2L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        duelService = mockk(relaxed = true)
        pendingDuelRegistry = mockk(relaxed = true)
        every { pendingDuelRegistry.ttl } returns Duration.ofMinutes(3)
        userDtoHelper = mockk(relaxed = true)
        command = DuelCommand(duelService, pendingDuelRegistry, userDtoHelper)
        every { guild.idLong } returns guildId
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun userOpt(target: User): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asUser } returns target
        return o
    }

    private fun intOpt(value: Long): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asLong } returns value
        return o
    }

    private fun targetUser(idLong: Long, isBot: Boolean = false): User {
        val u = mockk<User>(relaxed = true)
        every { u.idLong } returns idLong
        every { u.isBot } returns isBot
        return u
    }

    @Test
    fun `on Ok startDuel registers a pending offer`() {
        val initiator = UserDto(discordId = initiatorId, guildId = guildId).apply { socialCredit = 200L }
        val opponent = targetUser(opponentId)
        every { event.getOption("user") } returns userOpt(opponent)
        every { event.getOption("stake") } returns intOpt(50L)
        every {
            duelService.startDuel(initiatorId, opponentId, guildId, 50L)
        } returns DuelService.StartOutcome.Ok(initiatorBalance = 200L)
        every {
            pendingDuelRegistry.register(any(), any(), any(), any(), any(), any())
        } returns PendingDuelRegistry.PendingDuel(
            id = 99L, guildId = guildId,
            initiatorDiscordId = initiatorId, opponentDiscordId = opponentId,
            stake = 50L, createdAt = java.time.Instant.now()
        )

        command.handle(DefaultCommandContext(event), initiator, 5)

        verify(exactly = 1) {
            duelService.startDuel(initiatorId, opponentId, guildId, 50L)
        }
        verify(exactly = 1) {
            pendingDuelRegistry.register(guildId, initiatorId, opponentId, 50L, any(), any())
        }
    }

    @Test
    fun `bot opponent is rejected before service is called`() {
        val initiator = UserDto(discordId = initiatorId, guildId = guildId).apply { socialCredit = 200L }
        val bot = targetUser(opponentId, isBot = true)
        every { event.getOption("user") } returns userOpt(bot)
        every { event.getOption("stake") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), initiator, 5)

        verify(exactly = 0) { duelService.startDuel(any(), any(), any(), any()) }
        verify(exactly = 0) { pendingDuelRegistry.register(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `self opponent is rejected before service is called`() {
        val initiator = UserDto(discordId = initiatorId, guildId = guildId).apply { socialCredit = 200L }
        val self = targetUser(initiatorId)
        every { event.getOption("user") } returns userOpt(self)
        every { event.getOption("stake") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), initiator, 5)

        verify(exactly = 0) { duelService.startDuel(any(), any(), any(), any()) }
    }

    @Test
    fun `non-Ok start outcomes do not register an offer`() {
        val initiator = UserDto(discordId = initiatorId, guildId = guildId).apply { socialCredit = 5L }
        val opponent = targetUser(opponentId)
        every { event.getOption("user") } returns userOpt(opponent)
        every { event.getOption("stake") } returns intOpt(50L)
        every {
            duelService.startDuel(initiatorId, opponentId, guildId, 50L)
        } returns DuelService.StartOutcome.InitiatorInsufficient(have = 5L, needed = 50L)

        command.handle(DefaultCommandContext(event), initiator, 5)

        verify(exactly = 0) { pendingDuelRegistry.register(any(), any(), any(), any(), any(), any()) }
    }
}
