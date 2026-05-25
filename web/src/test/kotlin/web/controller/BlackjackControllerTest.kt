package web.controller

import common.casino.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.service.casino.blackjack.BlackjackService
import database.service.economy.JackpotGame
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.ConcurrentModel
import org.springframework.ui.Model
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import web.casino.CasinoPageContext
import web.casino.StakeBounds
import web.service.BlackjackWebService
import web.service.EconomyWebService
import common.casino.blackjack.canSplit

/**
 * Page-handler regression tests. The JSON endpoints (`/state`, `/deal`,
 * `/action`, …) live in their own integration coverage; this class
 * exists specifically to lock in that the three GET handlers route
 * through the shared [CasinoPageContext.populate] — earlier the
 * controller open-coded its own model wiring and missed updates to
 * the shared casino banner attributes (`jackpotPool`, `jackpotWinPct`,
 * `jackpotStakeAnchor`, `tobyCoins`, …) that every other casino page
 * got automatically.
 */
class BlackjackControllerTest {

    private val guildId = 42L
    private val discordId = 100L
    private val tableId = 7L

    private lateinit var blackjackService: BlackjackService
    private lateinit var blackjackWebService: BlackjackWebService
    private lateinit var tableRegistry: BlackjackTableRegistry
    private lateinit var economyWebService: EconomyWebService
    private lateinit var pageContext: CasinoPageContext
    private lateinit var stakeBounds: StakeBounds
    private lateinit var user: OAuth2User
    private lateinit var controller: BlackjackController

    @BeforeEach
    fun setup() {
        blackjackService = mockk(relaxed = true)
        blackjackWebService = mockk(relaxed = true)
        tableRegistry = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        pageContext = mockk(relaxed = true)
        stakeBounds = mockk(relaxed = true) {
            every { blackjackSolo(guildId) } returns (10L to 500L)
        }
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        // Default populate stub: returns a guild and stamps the canonical
        // casino attributes on the model so per-handler tests can assert
        // the centralised wiring fires.
        val guildStub = mockk<Guild>(relaxed = true).also { every { it.name } returns "Test Guild" }
        every { pageContext.populate(any(), guildId, discordId, user, any()) } answers {
            val model = arg<Model>(0)
            model.addAttribute("guildId", guildId.toString())
            model.addAttribute("guildName", "Test Guild")
            model.addAttribute("balance", 1L)
            model.addAttribute("jackpotPool", 1234L)
            model.addAttribute("jackpotWinPct", "0.0005")
            model.addAttribute("jackpotStakeAnchor", 500L)
            model.addAttribute("username", "tester")
            guildStub
        }
        controller = BlackjackController(
            blackjackService, blackjackWebService, tableRegistry,
            economyWebService, pageContext, stakeBounds,
        )
    }

    @Test
    fun `lobby page routes through CasinoPageContext for the shared banner attributes`() {
        every { blackjackWebService.listMultiTables(guildId) } returns emptyList()

        val model = ConcurrentModel()
        val view = controller.lobby(guildId, user, model, RedirectAttributesModelMap())

        assertEquals("blackjack-lobby", view)
        verify { pageContext.populate(model, guildId, discordId, user, JackpotGame.BLACKJACK) }
        // Centralised attributes — populated by populate(), proxied here:
        assertEquals(1234L, model.getAttribute("jackpotPool"))
        assertEquals("0.0005", model.getAttribute("jackpotWinPct"))
        assertEquals(500L, model.getAttribute("jackpotStakeAnchor"))
        // Lobby-specific attributes still get added via the rules lambda:
        assertEquals(10L, model.getAttribute("minAnte"))
        assertEquals(500L, model.getAttribute("maxAnte"))
    }

    @Test
    fun `soloPage routes through CasinoPageContext for the shared banner attributes`() {
        val model = ConcurrentModel()
        val view = controller.soloPage(guildId, user, model, RedirectAttributesModelMap())

        assertEquals("blackjack-solo", view)
        verify { pageContext.populate(model, guildId, discordId, user, JackpotGame.BLACKJACK) }
        assertEquals(1234L, model.getAttribute("jackpotPool"))
        assertEquals("0.0005", model.getAttribute("jackpotWinPct"))
        assertEquals(500L, model.getAttribute("jackpotStakeAnchor"))
        assertEquals(10L, model.getAttribute("minStake"))
        assertEquals(500L, model.getAttribute("maxStake"))
        assertEquals(discordId.toString(), model.getAttribute("myDiscordId"))
    }

    @Test
    fun `tablePage routes through CasinoPageContext for the shared banner attributes`() {
        val snapshot = sampleSnapshot()
        every { blackjackWebService.snapshot(tableId, discordId) } returns snapshot

        val model = ConcurrentModel()
        val view = controller.tablePage(
            guildId, tableId, user, model, RedirectAttributesModelMap()
        )

        assertEquals("blackjack-table", view)
        verify { pageContext.populate(model, guildId, discordId, user, JackpotGame.BLACKJACK) }
        assertEquals(1234L, model.getAttribute("jackpotPool"))
        assertEquals("0.0005", model.getAttribute("jackpotWinPct"))
        assertEquals(500L, model.getAttribute("jackpotStakeAnchor"))
        assertEquals(tableId.toString(), model.getAttribute("tableId"))
        assertEquals(100L, model.getAttribute("ante"))
        assertEquals(discordId.toString(), model.getAttribute("myDiscordId"))
    }

    private fun sampleSnapshot(): BlackjackWebService.TableStateView =
        BlackjackWebService.TableStateView(
            tableId = tableId,
            guildId = guildId,
            mode = BlackjackTable.Mode.MULTI.name,
            hostDiscordId = discordId.toString(),
            phase = "LOBBY",
            handNumber = 0L,
            ante = 100L,
            maxSeats = 5,
            actorIndex = 0,
            seats = emptyList(),
            dealer = emptyList(),
            dealerTotalVisible = 0,
            mySeatIndex = null,
            isMyTurn = false,
            canDouble = false,
            canSplit = false,
            shotClockSeconds = 0,
            currentActorDeadlineEpochMillis = null,
            lastResult = null,
        )
}
