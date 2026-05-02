package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.guild
import bot.toby.command.CommandTest.Companion.webhookMessageCreateAction
import bot.toby.command.DefaultCommandContext
import database.dto.UserDto
import database.economy.Baccarat
import database.service.BaccaratService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class BaccaratCommandTest : CommandTest {
    private lateinit var baccaratService: BaccaratService
    private lateinit var command: BaccaratCommand

    private val discordId = 1L
    private val guildId = 42L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        baccaratService = mockk(relaxed = true)
        every { baccaratService.previewMultiplier(Baccarat.Side.PLAYER) } returns 2.0
        every { baccaratService.previewMultiplier(Baccarat.Side.BANKER) } returns 1.95
        every { baccaratService.previewMultiplier(Baccarat.Side.TIE) } returns 9.0
        command = BaccaratCommand(baccaratService)
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
    fun `posts the prompt embed and three side buttons without resolving the wager`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns intOpt(50L)

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { baccaratService.play(any(), any(), any(), any(), any()) }
        verify { event.hook.sendMessageEmbeds(any<MessageEmbed>()) }
        verify { webhookMessageCreateAction.addComponents(any<ActionRow>()) }
    }

    @Test
    fun `encodes side stake and user into each button component id`() {
        val stake = 50L
        Baccarat.Side.entries.forEach { side ->
            val id = BaccaratEmbeds.sideButtonId(side, stake, discordId)
            val parsed = BaccaratEmbeds.parseButtonId(id)
            assertNotNull(parsed)
            assertEquals(side, parsed!!.side)
            assertEquals(stake, parsed.stake)
            assertEquals(discordId, parsed.userId)
        }
    }

    @Test
    fun `does not invoke the service when no stake is provided`() {
        val user = UserDto(discordId = discordId, guildId = guildId)
        every { event.getOption("stake") } returns null

        command.handle(DefaultCommandContext(event), user, 5)

        verify(exactly = 0) { baccaratService.play(any(), any(), any(), any(), any()) }
    }
}
