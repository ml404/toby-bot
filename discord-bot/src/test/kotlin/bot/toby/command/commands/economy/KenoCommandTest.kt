package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import database.economy.Keno
import database.service.KenoService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class KenoCommandTest : CommandTest {
    private lateinit var kenoService: KenoService
    private lateinit var command: KenoCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        kenoService = mockk(relaxed = true)
        command = KenoCommand(kenoService)
        every { guild.idLong } returns guildId
        every { kenoService.quickPick(any()) } answers {
            val n = firstArg<Int>()
            (1..n).toList()
        }
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun intOpt(value: Long): OptionMapping = mockk<OptionMapping>(relaxed = true).also {
        every { it.asLong } returns value
    }

    private fun stringOpt(value: String): OptionMapping = mockk<OptionMapping>(relaxed = true).also {
        every { it.asString } returns value
    }

    @Test
    fun `default invocation quick-picks 5 spots and resolves through the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(50L)
        every { event.getOption("spots") } returns null
        every { event.getOption("picks") } returns null
        every { kenoService.quickPick(5) } returns listOf(7, 12, 18, 33, 71)
        every { kenoService.play(discordId, guildId, 50L, listOf(7, 12, 18, 33, 71)) } returns
            KenoService.PlayOutcome.Lose(
                stake = 50L, picks = listOf(7, 12, 18, 33, 71),
                draws = (1..20).toList(), hits = 0, newBalance = 950L
            )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { kenoService.quickPick(5) }
        verify(exactly = 1) { kenoService.play(discordId, guildId, 50L, listOf(7, 12, 18, 33, 71)) }
        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `explicit spots option overrides the default and is forwarded to quickPick`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(100L)
        every { event.getOption("spots") } returns intOpt(8L)
        every { event.getOption("picks") } returns null
        every { kenoService.quickPick(8) } returns (1..8).toList()
        every { kenoService.play(discordId, guildId, 100L, (1..8).toList()) } returns
            KenoService.PlayOutcome.Lose(
                stake = 100L, picks = (1..8).toList(),
                draws = (50..69).toList(), hits = 0, newBalance = 900L
            )

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { kenoService.quickPick(8) }
    }

    @Test
    fun `user-supplied picks CSV is parsed and forwarded verbatim`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(100L)
        every { event.getOption("spots") } returns intOpt(5L)
        every { event.getOption("picks") } returns stringOpt("7, 12, 18, 33, 71")
        val capturedPicks = slot<List<Int>>()
        every { kenoService.play(discordId, guildId, 100L, capture(capturedPicks)) } returns
            KenoService.PlayOutcome.Win(
                stake = 100L, payout = 1_460L, net = 1_360L,
                picks = listOf(7, 12, 18, 33, 71),
                draws = (1..20).toList(), hits = 2, multiplier = 14.6,
                newBalance = 2_360L
            )

        command.handle(DefaultCommandContext(event), user, 5)

        assertEquals(listOf(7, 12, 18, 33, 71), capturedPicks.captured)
        verify(exactly = 0) { kenoService.quickPick(any()) }
    }

    @Test
    fun `picks count mismatch surfaces an error embed and does not call the service`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(100L)
        every { event.getOption("spots") } returns intOpt(5L)
        // Only 3 picks supplied for a 5-spot request.
        every { event.getOption("picks") } returns stringOpt("7, 12, 18")

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { kenoService.play(any(), any(), any(), any(), any()) }
        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
    }

    @Test
    fun `out-of-pool pick surfaces an error embed`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(100L)
        every { event.getOption("spots") } returns intOpt(2L)
        every { event.getOption("picks") } returns stringOpt("5, 99")

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { kenoService.play(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate picks surface an error embed`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(100L)
        every { event.getOption("spots") } returns intOpt(3L)
        every { event.getOption("picks") } returns stringOpt("5, 5, 7")

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { kenoService.play(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `non-numeric pick surfaces an error embed`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(100L)
        every { event.getOption("spots") } returns intOpt(2L)
        every { event.getOption("picks") } returns stringOpt("5, abc")

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { kenoService.play(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `does not invoke the service when no stake is provided`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { kenoService.play(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `parsePicks helper returns null for empty parts after trimming`() {
        // ", , , ," with spots=4 should produce 0 valid parts → mismatch.
        assertNull(command.parsePicks(", , , ,", 4))
    }

    @Test
    fun `parsePicks helper happy path returns the parsed list`() {
        assertEquals(listOf(1, 2, 3), command.parsePicks("1,2,3", 3))
    }

    @Test
    fun `parsePicks helper quick-picks when raw is blank`() {
        every { kenoService.quickPick(5) } returns listOf(11, 22, 33, 44, 55)
        assertEquals(listOf(11, 22, 33, 44, 55), command.parsePicks("", 5))
        assertEquals(listOf(11, 22, 33, 44, 55), command.parsePicks(null, 5))
    }

    @Test
    fun `parsePicks rejects spots count outside the engine range`() {
        assertNull(command.parsePicks(null, 0))
        assertNull(command.parsePicks(null, Keno.MAX_SPOTS + 1))
    }
}
