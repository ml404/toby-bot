package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import database.economy.Highlow
import database.service.HighlowService
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

internal class HighlowCommandTest : CommandTest {
    private lateinit var highlowService: HighlowService
    private lateinit var command: HighlowCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        highlowService = mockk(relaxed = true)
        command = HighlowCommand(highlowService)
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

    @Test
    fun `deals anchor and sends embed with direction buttons without resolving the wager`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(50L)
        every { highlowService.dealAnchor() } returns 7

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 1) { highlowService.dealAnchor() }
        verify(exactly = 0) { highlowService.play(any(), any(), any(), any()) }
        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify { webhookMessageCreateAction.addComponents(any<ActionRow>()) }
    }

    @Test
    fun `encodes direction anchor stake and user into the button component ids`() {
        val anchor = 9
        val stake = 50L
        val higherId = HighlowEmbeds.directionButtonId(Highlow.Direction.HIGHER, anchor, stake, discordId)
        val lowerId = HighlowEmbeds.directionButtonId(Highlow.Direction.LOWER, anchor, stake, discordId)

        // Round-trip through the same parser HighlowButton uses.
        val parsedHigher = HighlowEmbeds.parseButtonId(higherId)
        val parsedLower = HighlowEmbeds.parseButtonId(lowerId)

        check(parsedHigher != null && parsedHigher.direction == Highlow.Direction.HIGHER &&
              parsedHigher.anchor == anchor && parsedHigher.stake == stake &&
              parsedHigher.userId == discordId)
        check(parsedLower != null && parsedLower.direction == Highlow.Direction.LOWER &&
              parsedLower.anchor == anchor && parsedLower.stake == stake &&
              parsedLower.userId == discordId)
    }

    @Test
    fun `does not call play or deal an anchor when no stake is provided`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { highlowService.dealAnchor() }
        verify(exactly = 0) { highlowService.play(any(), any(), any(), any()) }
    }
}
