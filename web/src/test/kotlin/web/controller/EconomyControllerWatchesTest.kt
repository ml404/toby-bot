package web.controller

import database.dto.economy.UserPriceTriggerDto
import database.service.social.SocialCreditAwardService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.service.CreateWatchResult
import web.service.EconomyWebService
import web.service.WatchView
import java.time.Instant

/**
 * Covers the three /economy/{guildId}/watches endpoints — list/create/delete.
 * The membership guard (401/403) and the parity-rejection / invalid-side /
 * invalid-amount / not-found branches are the ones a future regression would
 * silently break, so each gets its own case.
 */
class EconomyControllerWatchesTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var economyWebService: EconomyWebService
    private lateinit var awardService: SocialCreditAwardService
    private lateinit var user: OAuth2User
    private lateinit var controller: EconomyController

    @BeforeEach
    fun setup() {
        economyWebService = mockk(relaxed = true)
        awardService = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = EconomyController(economyWebService, awardService)
    }

    private fun anonUser(): OAuth2User = mockk {
        every { getAttribute<String>("id") } returns null
        every { getAttribute<String>("username") } returns null
    }

    private fun sampleView(id: Long = 1L) = WatchView(
        id = id,
        side = "BUY",
        amount = 5L,
        thresholdPrice = 80.0,
        priceAtCreation = 100.0,
        enabled = true,
        firedAt = null,
        createdAt = Instant.ofEpochMilli(1_000_000L),
    )

    @Test
    fun `list returns 200 with the service result and current price`() {
        every { economyWebService.listWatches(discordId, guildId) } returns listOf(sampleView(7L))
        every { economyWebService.currentPrice(guildId) } returns 100.0

        val response = controller.listWatches(guildId, user)

        assertTrue(response.statusCode.is2xxSuccessful)
        assertEquals(true, response.body?.ok)
        assertEquals(1, response.body?.watches?.size)
        assertEquals(7L, response.body?.watches?.first()?.id)
        assertEquals(100.0, response.body?.price)
    }

    @Test
    fun `list returns 403 for non-member`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.listWatches(guildId, user)

        assertEquals(403, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        verify(exactly = 0) { economyWebService.listWatches(any(), any()) }
    }

    @Test
    fun `list returns 401 for unauthenticated principal`() {
        val response = controller.listWatches(guildId, anonUser())

        assertEquals(401, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        verify(exactly = 0) { economyWebService.listWatches(any(), any()) }
    }

    @Test
    fun `create returns 200 with notificationsAutoEnabled true when pref flipped`() {
        every {
            economyWebService.createWatch(
                discordId, guildId, 80.0, UserPriceTriggerDto.Side.BUY, 5L
            )
        } returns CreateWatchResult.Ok(sampleView(), notificationsAutoEnabled = true)

        val response = controller.createWatch(
            guildId,
            CreateWatchRequest(threshold = 80.0, side = "BUY", amount = 5L),
            user
        )

        assertTrue(response.statusCode.is2xxSuccessful)
        assertEquals(true, response.body?.ok)
        assertEquals(true, response.body?.notificationsAutoEnabled)
        assertNotNull(response.body?.watch)
        assertEquals(80.0, response.body?.watch?.threshold)
    }

    @Test
    fun `create returns 200 with notificationsAutoEnabled false when pref already on`() {
        every {
            economyWebService.createWatch(
                discordId, guildId, 80.0, UserPriceTriggerDto.Side.BUY, 5L
            )
        } returns CreateWatchResult.Ok(sampleView(), notificationsAutoEnabled = false)

        val response = controller.createWatch(
            guildId,
            CreateWatchRequest(threshold = 80.0, side = "BUY", amount = 5L),
            user
        )

        assertEquals(false, response.body?.notificationsAutoEnabled)
    }

    @Test
    fun `create returns 400 on parity rejection with the exact message text`() {
        every {
            economyWebService.createWatch(
                discordId, guildId, 100.0, UserPriceTriggerDto.Side.BUY, 5L
            )
        } returns CreateWatchResult.ParityRejected(threshold = 100.0, currentPrice = 100.0)

        val response = controller.createWatch(
            guildId,
            CreateWatchRequest(threshold = 100.0, side = "BUY", amount = 5L),
            user
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(false, response.body?.ok)
        assertTrue(
            response.body?.error?.contains("essentially the current price") == true,
            "expected parity-rejection wording, got: ${response.body?.error}"
        )
    }

    @Test
    fun `create returns 400 on InvalidAmount from service`() {
        every {
            economyWebService.createWatch(
                discordId, guildId, 80.0, UserPriceTriggerDto.Side.BUY, 5L
            )
        } returns CreateWatchResult.InvalidAmount

        val response = controller.createWatch(
            guildId,
            CreateWatchRequest(threshold = 80.0, side = "BUY", amount = 5L),
            user
        )

        assertEquals(400, response.statusCode.value())
        assertEquals("Amount must be a positive number.", response.body?.error)
    }

    @Test
    fun `create returns 400 on bad side and never calls the service`() {
        val response = controller.createWatch(
            guildId,
            CreateWatchRequest(threshold = 80.0, side = "WOBBLE", amount = 5L),
            user
        )

        assertEquals(400, response.statusCode.value())
        assertEquals("Side must be BUY or SELL.", response.body?.error)
        verify(exactly = 0) {
            economyWebService.createWatch(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `create returns 400 on missing fields and never calls the service`() {
        // amount = 0 (default) triggers the missing-field branch even when
        // side and threshold look valid.
        val response = controller.createWatch(
            guildId,
            CreateWatchRequest(threshold = 80.0, side = "BUY", amount = 0L),
            user
        )

        assertEquals(400, response.statusCode.value())
        assertEquals("Missing field.", response.body?.error)
        verify(exactly = 0) {
            economyWebService.createWatch(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `create returns 403 for non-member`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.createWatch(
            guildId,
            CreateWatchRequest(threshold = 80.0, side = "BUY", amount = 5L),
            user
        )

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) {
            economyWebService.createWatch(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `delete returns 200 on success`() {
        every { economyWebService.removeWatch(99L, discordId) } returns true

        val response = controller.deleteWatch(guildId, 99L, user)

        assertTrue(response.statusCode.is2xxSuccessful)
        assertEquals(true, response.body?.ok)
        assertNull(response.body?.error)
    }

    @Test
    fun `delete returns 404 when service returns false`() {
        every { economyWebService.removeWatch(99L, discordId) } returns false

        val response = controller.deleteWatch(guildId, 99L, user)

        assertEquals(404, response.statusCode.value())
        assertFalse(response.body?.ok == true)
    }

    @Test
    fun `delete returns 403 for non-member`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.deleteWatch(guildId, 99L, user)

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { economyWebService.removeWatch(any(), any()) }
    }
}
