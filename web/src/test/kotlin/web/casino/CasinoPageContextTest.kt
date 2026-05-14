package web.casino

import database.dto.UserDto
import database.service.JackpotGame
import database.service.JackpotService
import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.ConcurrentModel

/**
 * Behavioural coverage for the per-game RTP eligibility wiring in
 * [CasinoPageContext.populate]. The banner-fragment unit test asserts
 * the template renders the right blocks; this asserts the model arrives
 * with the right attributes set.
 */
class CasinoPageContextTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var jda: JDA
    private lateinit var guild: Guild
    private lateinit var user: OAuth2User
    private lateinit var ctx: CasinoPageContext

    private val guildId = 200L
    private val discordId = 7L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        guild = mockk(relaxed = true)
        user = mockk(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.name } returns "Guild"
        every { user.getAttribute<String>("username") } returns "tester"
        every { userService.getUserById(discordId, guildId) } returns UserDto(
            discordId = discordId, guildId = guildId
        )
        every { marketService.getMarket(guildId) } returns null
        every { jackpotService.getPool(guildId) } returns 0L
        every { jackpotService.winProbabilityDisplay(guildId) } returns "1"
        every { jackpotService.stakeAnchor(guildId) } returns 500L
        ctx = CasinoPageContext(userService, jackpotService, marketService, jda)
    }

    @Test
    fun `populate omits ineligibility attrs when game is null (lottery, poker share the banner)`() {
        val model = ConcurrentModel()

        val resolved = ctx.populate(model, guildId, discordId, user, game = null)

        assertNotNull(resolved)
        assertEquals(false, model.containsAttribute("jackpotIneligible"))
        assertEquals(false, model.containsAttribute("jackpotRtpMax"))
        // Eligibility was not consulted because the page didn't declare a game.
        verify(exactly = 0) { jackpotService.isEligibleByRtp(any(), any()) }
    }

    @Test
    fun `populate omits ineligibility attrs when the game is eligible`() {
        every { jackpotService.isEligibleByRtp(guildId, JackpotGame.SLOTS) } returns true
        val model = ConcurrentModel()

        ctx.populate(model, guildId, discordId, user, game = JackpotGame.SLOTS)

        assertEquals(false, model.containsAttribute("jackpotIneligible"))
        assertEquals(false, model.containsAttribute("jackpotRtpMax"))
    }

    @Test
    fun `populate stamps RTP-reason ineligibility attrs when the per-guild RTP gate blocks the game`() {
        every { jackpotService.isEligibleByRtp(guildId, JackpotGame.COINFLIP) } returns false
        every { jackpotService.rtpMaxPct(guildId) } returns 95L
        val model = ConcurrentModel()

        ctx.populate(model, guildId, discordId, user, game = JackpotGame.COINFLIP)

        assertEquals(true, model.getAttribute("jackpotIneligible"))
        assertEquals("rtp", model.getAttribute("jackpotIneligibleReason"))
        assertEquals(95L, model.getAttribute("jackpotRtpMax"))
    }

    @Test
    fun `populate stamps structural-reason ineligibility for HIGHLOW regardless of RTP gate`() {
        // HIGHLOW carries JackpotGame.eligibleForJackpot=false. The RTP gate
        // never matters for it — banner shows the win-rate reason instead.
        val model = ConcurrentModel()

        ctx.populate(model, guildId, discordId, user, game = JackpotGame.HIGHLOW)

        assertEquals(true, model.getAttribute("jackpotIneligible"))
        assertEquals("structural", model.getAttribute("jackpotIneligibleReason"))
        assertEquals(false, model.containsAttribute("jackpotRtpMax"))
        // The RTP gate was not even consulted — the structural carve-out
        // short-circuits before the per-guild check.
        verify(exactly = 0) { jackpotService.isEligibleByRtp(any(), any()) }
        verify(exactly = 0) { jackpotService.rtpMaxPct(any()) }
    }
}
