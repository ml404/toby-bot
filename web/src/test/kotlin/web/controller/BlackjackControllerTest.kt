package web.controller

import database.blackjack.BlackjackTable
import database.blackjack.BlackjackTableRegistry
import database.service.BlackjackService
import database.service.JackpotService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.ui.ConcurrentModel
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import web.service.BlackjackWebService
import web.service.EconomyWebService

/**
 * Page-handler regression tests. The JSON endpoints (`/state`, `/deal`,
 * `/action`, …) live in their own integration coverage; this class
 * exists specifically to lock in the model attributes that drive the
 * Thymeleaf templates — the original miss was that `jackpotPool` was
 * never wired through, so the shared `~{fragments/casino :: jackpotBanner}`
 * fragment renders blank on every blackjack page.
 */
class BlackjackControllerTest {

    private val guildId = 42L
    private val discordId = 100L
    private val tableId = 7L

    private lateinit var blackjackService: BlackjackService
    private lateinit var blackjackWebService: BlackjackWebService
    private lateinit var tableRegistry: BlackjackTableRegistry
    private lateinit var economyWebService: EconomyWebService
    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var jda: JDA
    private lateinit var user: OAuth2User
    private lateinit var controller: BlackjackController

    @BeforeEach
    fun setup() {
        blackjackService = mockk(relaxed = true)
        blackjackWebService = mockk(relaxed = true)
        tableRegistry = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = BlackjackController(
            blackjackService, blackjackWebService, tableRegistry,
            economyWebService, userService, jackpotService, jda,
        )
    }

    @Test
    fun `lobby page wires jackpotPool and jackpotWinPct model attributes so the banner renders`() {
        val guild = mockk<Guild>(relaxed = true).also {
            every { it.name } returns "Test Guild"
        }
        every { jda.getGuildById(guildId) } returns guild
        every { jackpotService.getPool(guildId) } returns 1234L
        every { jackpotService.winProbabilityPct(guildId) } returns 2.5
        every { blackjackWebService.listMultiTables(guildId) } returns emptyList()

        val model = ConcurrentModel()
        val view = controller.lobby(guildId, user, model, RedirectAttributesModelMap())

        assertEquals("blackjack-lobby", view)
        assertEquals(1234L, model.getAttribute("jackpotPool"))
        assertEquals(2.5, model.getAttribute("jackpotWinPct"))
    }

    @Test
    fun `soloPage wires jackpotPool and jackpotWinPct model attributes so the banner renders`() {
        every { jackpotService.getPool(guildId) } returns 5555L
        every { jackpotService.winProbabilityPct(guildId) } returns 1.0

        val model = ConcurrentModel()
        val view = controller.soloPage(guildId, user, model, RedirectAttributesModelMap())

        assertEquals("blackjack-solo", view)
        assertEquals(5555L, model.getAttribute("jackpotPool"))
        assertEquals(1.0, model.getAttribute("jackpotWinPct"))
    }

    @Test
    fun `tablePage wires jackpotPool and jackpotWinPct model attributes so the banner renders`() {
        // Snapshot must match the requested guild and be a MULTI table or
        // the controller short-circuits to redirect — match the production
        // shape so we exercise the model-attribute branch.
        val snapshot = sampleSnapshot()
        every { blackjackWebService.snapshot(tableId, discordId) } returns snapshot
        every { jackpotService.getPool(guildId) } returns 9876L
        every { jackpotService.winProbabilityPct(guildId) } returns 5.0

        val model = ConcurrentModel()
        val view = controller.tablePage(
            guildId, tableId, user, model, RedirectAttributesModelMap()
        )

        assertEquals("blackjack-table", view)
        assertEquals(9876L, model.getAttribute("jackpotPool"))
        assertEquals(5.0, model.getAttribute("jackpotWinPct"))
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
