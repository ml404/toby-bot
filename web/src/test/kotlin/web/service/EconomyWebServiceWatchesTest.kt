package web.service

import common.notification.NotificationChannelKind
import common.notification.Surface
import database.dto.TobyCoinMarketDto
import database.dto.UserPriceTriggerDto
import database.service.economy.EconomyTradeService
import database.service.economy.TobyCoinMarketService
import database.service.user.UserNotificationPrefService
import database.service.economy.UserPriceTriggerService
import database.service.user.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import web.util.GuildMembership
import java.time.Instant

/**
 * Mirrors PriceAlertCommand's contract on the web service surface:
 * parity-epsilon rejection, opt-in auto-flip, and the obvious passthroughs.
 * These cases are duplicated *outside* EconomyWebServiceTest because the
 * watch path needs two extra collaborators (UserPriceTriggerService and
 * UserNotificationPrefService) that don't matter for any of the wallet/
 * trade tests there.
 */
class EconomyWebServiceWatchesTest {

    private lateinit var jda: JDA
    private lateinit var introWebService: IntroWebService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var userService: UserService
    private lateinit var priceTriggerService: UserPriceTriggerService
    private lateinit var notificationPrefService: UserNotificationPrefService
    private lateinit var service: EconomyWebService

    private val guildId = 42L
    private val discordId = 100L

    @BeforeEach
    fun setup() {
        jda = mockk(relaxed = true)
        introWebService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        priceTriggerService = mockk(relaxed = true)
        notificationPrefService = mockk(relaxed = true)
        service = EconomyWebService(
            jda, introWebService, tradeService, marketService, userService,
            GuildMembership(jda), priceTriggerService, notificationPrefService,
        )
    }

    private fun market(price: Double) =
        TobyCoinMarketDto(guildId = guildId, price = price, lastTickAt = Instant.now())

    @Test
    fun `createWatch rejects threshold within parity epsilon of current price`() {
        every { tradeService.loadOrCreateMarket(guildId) } returns market(100.0)

        // Inside the 1e-4 epsilon (0.00005 < 0.0001), so this must be rejected.
        val result = service.createWatch(
            discordId, guildId, threshold = 100.00005,
            side = UserPriceTriggerDto.Side.BUY, amount = 1L,
        )

        assertTrue(result is CreateWatchResult.ParityRejected, "got $result")
        verify(exactly = 0) {
            priceTriggerService.create(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `createWatch accepts threshold just outside parity epsilon`() {
        every { tradeService.loadOrCreateMarket(guildId) } returns market(100.0)
        every {
            priceTriggerService.create(
                discordId, guildId, 100.0002, 100.0,
                UserPriceTriggerDto.Side.BUY, 1L,
            )
        } returns UserPriceTriggerDto(
            id = 1L, discordId = discordId, guildId = guildId,
            thresholdPrice = 100.0002, priceAtCreation = 100.0,
            side = UserPriceTriggerDto.Side.BUY.name, amount = 1L,
        )

        val result = service.createWatch(
            discordId, guildId, threshold = 100.0002,
            side = UserPriceTriggerDto.Side.BUY, amount = 1L,
        )

        assertTrue(result is CreateWatchResult.Ok, "got $result")
    }

    @Test
    fun `createWatch auto-enables PRICE_ALERT DM when user was opted out`() {
        every { tradeService.loadOrCreateMarket(guildId) } returns market(100.0)
        every {
            priceTriggerService.create(any(), any(), any(), any(), any(), any())
        } returns UserPriceTriggerDto(
            id = 1L, discordId = discordId, guildId = guildId,
            thresholdPrice = 80.0, priceAtCreation = 100.0,
            side = UserPriceTriggerDto.Side.BUY.name, amount = 5L,
        )
        every {
            notificationPrefService.isOptedIn(
                discordId, guildId,
                NotificationChannelKind.PRICE_ALERT, Surface.DM,
            )
        } returns false

        val result = service.createWatch(
            discordId, guildId, threshold = 80.0,
            side = UserPriceTriggerDto.Side.BUY, amount = 5L,
        )

        assertTrue(result is CreateWatchResult.Ok)
        assertTrue((result as CreateWatchResult.Ok).notificationsAutoEnabled)
        verify(exactly = 1) {
            notificationPrefService.setPref(
                discordId, guildId,
                NotificationChannelKind.PRICE_ALERT, Surface.DM, optIn = true,
            )
        }
    }

    @Test
    fun `createWatch does not touch prefs when user was already opted in`() {
        every { tradeService.loadOrCreateMarket(guildId) } returns market(100.0)
        every {
            priceTriggerService.create(any(), any(), any(), any(), any(), any())
        } returns UserPriceTriggerDto(
            id = 1L, discordId = discordId, guildId = guildId,
            thresholdPrice = 80.0, priceAtCreation = 100.0,
            side = UserPriceTriggerDto.Side.BUY.name, amount = 5L,
        )
        every {
            notificationPrefService.isOptedIn(
                discordId, guildId,
                NotificationChannelKind.PRICE_ALERT, Surface.DM,
            )
        } returns true

        val result = service.createWatch(
            discordId, guildId, threshold = 80.0,
            side = UserPriceTriggerDto.Side.BUY, amount = 5L,
        )

        assertTrue(result is CreateWatchResult.Ok)
        assertFalse((result as CreateWatchResult.Ok).notificationsAutoEnabled)
        verify(exactly = 0) {
            notificationPrefService.setPref(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `createWatch returns InvalidAmount on non-positive amount and never loads market`() {
        val result = service.createWatch(
            discordId, guildId, threshold = 80.0,
            side = UserPriceTriggerDto.Side.BUY, amount = 0L,
        )

        assertSame(CreateWatchResult.InvalidAmount, result)
        verify(exactly = 0) { tradeService.loadOrCreateMarket(any()) }
        verify(exactly = 0) {
            priceTriggerService.create(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `listWatches maps DTOs to WatchView including firedAt and enabled passthrough`() {
        val firedAt = Instant.ofEpochSecond(1_700_000_000L)
        val createdAt = Instant.ofEpochSecond(1_699_000_000L)
        every { priceTriggerService.listForUser(discordId, guildId) } returns listOf(
            UserPriceTriggerDto(
                id = 1L, discordId = discordId, guildId = guildId,
                thresholdPrice = 80.0, priceAtCreation = 100.0,
                side = UserPriceTriggerDto.Side.BUY.name, amount = 5L,
                enabled = false, firedAt = firedAt, createdAt = createdAt,
            ),
            UserPriceTriggerDto(
                id = 2L, discordId = discordId, guildId = guildId,
                thresholdPrice = 150.0, priceAtCreation = 100.0,
                side = UserPriceTriggerDto.Side.SELL.name, amount = 3L,
                enabled = true, firedAt = null, createdAt = createdAt,
            ),
        )

        val views = service.listWatches(discordId, guildId)

        assertEquals(2, views.size)
        assertEquals(1L, views[0].id)
        assertFalse(views[0].enabled)
        assertEquals(firedAt, views[0].firedAt)
        assertEquals("BUY", views[0].side)
        assertTrue(views[1].enabled)
        assertEquals("SELL", views[1].side)
    }

    @Test
    fun `removeWatch passes through service result (true)`() {
        every { priceTriggerService.remove(99L, discordId) } returns true
        assertTrue(service.removeWatch(99L, discordId))
    }

    @Test
    fun `removeWatch passes through service result (false)`() {
        every { priceTriggerService.remove(99L, discordId) } returns false
        assertFalse(service.removeWatch(99L, discordId))
    }
}
