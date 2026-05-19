package bot.toby.command.commands.economy

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.CommandTest.Companion.interactionHook
import bot.toby.command.DefaultCommandContext
import common.notification.NotificationChannelKind
import common.notification.Surface
import database.dto.TobyCoinMarketDto
import database.dto.UserDto
import database.dto.UserPriceTriggerDto
import database.service.EconomyTradeService
import database.service.UserNotificationPrefService
import database.service.UserPriceTriggerService
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

internal class PriceAlertCommandTest : CommandTest {

    private lateinit var triggerService: UserPriceTriggerService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var prefService: UserNotificationPrefService
    private lateinit var command: PriceAlertCommand

    private val discordId = 1L
    private val guildId = 1L

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        triggerService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        prefService = mockk(relaxed = true)
        command = PriceAlertCommand(triggerService, tradeService, prefService)

        every { event.deferReply(true) } returns CommandTest.replyCallbackAction
        every { tradeService.loadOrCreateMarket(guildId) } returns
                TobyCoinMarketDto(guildId = guildId, price = 100.0, lastTickAt = Instant.now())
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        clearAllMocks()
    }

    private fun stringOpt(value: String): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asString } returns value
        return o
    }

    private fun longOpt(value: Long): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asLong } returns value
        return o
    }

    private fun doubleOpt(value: Double): OptionMapping {
        val o = mockk<OptionMapping>(relaxed = true)
        every { o.asDouble } returns value
        return o
    }

    @Test
    fun `add rejects threshold equal to current price`() {
        every { event.subcommandName } returns "add"
        every { event.getOption("price") } returns doubleOpt(100.0)
        every { event.getOption("side") } returns stringOpt("BUY")
        every { event.getOption("amount") } returns longOpt(5L)

        command.handle(DefaultCommandContext(event), UserDto(discordId, guildId), 5)

        // Parity branch sends an ephemeral text reply (not embed) and
        // never reaches the service.
        verify(exactly = 0) {
            triggerService.create(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `add creates trigger and auto-enables PRICE_ALERT DM when opted-out`() {
        every { event.subcommandName } returns "add"
        every { event.getOption("price") } returns doubleOpt(80.0)
        every { event.getOption("side") } returns stringOpt("BUY")
        every { event.getOption("amount") } returns longOpt(5L)
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.DM)
        } returns false
        val created = UserPriceTriggerDto(
            id = 42L,
            discordId = discordId, guildId = guildId,
            thresholdPrice = 80.0, priceAtCreation = 100.0,
            side = UserPriceTriggerDto.Side.BUY.name, amount = 5L,
        )
        every {
            triggerService.create(discordId, guildId, 80.0, 100.0, UserPriceTriggerDto.Side.BUY, 5L)
        } returns created

        // Capture the embed sent back so we can assert the direction copy.
        val embedSlot = slot<MessageEmbed>()
        every {
            interactionHook.sendMessageEmbeds(capture(embedSlot))
        } returns mockk<WebhookMessageCreateAction<net.dv8tion.jda.api.entities.Message>>(relaxed = true)

        command.handle(DefaultCommandContext(event), UserDto(discordId, guildId), 5)

        verify(exactly = 1) {
            triggerService.create(discordId, guildId, 80.0, 100.0, UserPriceTriggerDto.Side.BUY, 5L)
        }
        verify(exactly = 1) {
            prefService.setPref(
                discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.DM, optIn = true
            )
        }
        val desc = embedSlot.captured.description.orEmpty()
        assertTrue(desc.contains("drop"), "downward target must read 'drop': $desc")
        assertTrue(desc.contains("PRICE_ALERT"), "should warn that PRICE_ALERT was enabled: $desc")
    }

    @Test
    fun `add does not flip pref when already opted-in`() {
        every { event.subcommandName } returns "add"
        every { event.getOption("price") } returns doubleOpt(150.0)
        every { event.getOption("side") } returns stringOpt("SELL")
        every { event.getOption("amount") } returns longOpt(3L)
        every {
            prefService.isOptedIn(discordId, guildId, NotificationChannelKind.PRICE_ALERT, Surface.DM)
        } returns true
        every {
            triggerService.create(any(), any(), any(), any(), any(), any())
        } returns UserPriceTriggerDto(id = 1L, discordId = discordId, guildId = guildId)

        command.handle(DefaultCommandContext(event), UserDto(discordId, guildId), 5)

        verify(exactly = 0) {
            prefService.setPref(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `remove enforces ownership through the service`() {
        every { event.subcommandName } returns "remove"
        every { event.getOption("id") } returns longOpt(99L)
        every { triggerService.remove(99L, discordId) } returns false

        command.handle(DefaultCommandContext(event), UserDto(discordId, guildId), 5)

        verify(exactly = 1) { triggerService.remove(99L, discordId) }
    }
}
