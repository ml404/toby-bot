package web.controller

import database.economy.SlotMachine
import database.service.JackpotService
import database.service.ScratchService
import database.service.ScratchService.ScratchOutcome
import database.service.TobyCoinMarketService
import database.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.user.OAuth2User
import web.service.EconomyWebService

class ScratchControllerTest {

    private val guildId = 42L
    private val discordId = 100L

    private lateinit var scratchService: ScratchService
    private lateinit var economyWebService: EconomyWebService
    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var jda: JDA
    private lateinit var user: OAuth2User
    private lateinit var controller: ScratchController

    @BeforeEach
    fun setup() {
        scratchService = mockk(relaxed = true)
        economyWebService = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        jda = mockk(relaxed = true)
        user = mockk {
            every { getAttribute<String>("id") } returns discordId.toString()
            every { getAttribute<String>("username") } returns "tester"
        }
        every { economyWebService.isMember(discordId, guildId) } returns true
        controller = ScratchController(scratchService, economyWebService, userService, jackpotService, marketService, jda)
    }

    @Test
    fun `scratch win returns 200 with cells and winning symbol`() {
        every { scratchService.scratch(discordId, guildId, 100L) } returns ScratchOutcome.Win(
            stake = 100L, payout = 3_000L, net = 2_900L,
            cells = List(5) { SlotMachine.Symbol.STAR },
            winningSymbol = SlotMachine.Symbol.STAR,
            matchCount = 5,
            newBalance = 3_900L
        )

        val response = controller.scratch(guildId, ScratchRequest(stake = 100L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(true, body.ok)
        assertEquals(true, body.win)
        assertEquals(2_900L, body.net)
        assertEquals(5, body.matchCount)
        assertEquals("⭐", body.winningSymbol)
        assertEquals(listOf("⭐", "⭐", "⭐", "⭐", "⭐"), body.cells)
    }

    @Test
    fun `scratch lose returns 200 with cells but no winning symbol`() {
        every { scratchService.scratch(discordId, guildId, 50L) } returns ScratchOutcome.Lose(
            stake = 50L,
            cells = listOf(
                SlotMachine.Symbol.CHERRY, SlotMachine.Symbol.LEMON,
                SlotMachine.Symbol.BELL, SlotMachine.Symbol.STAR, SlotMachine.Symbol.CHERRY
            ),
            newBalance = 950L
        )

        val response = controller.scratch(guildId, ScratchRequest(stake = 50L), user)

        assertTrue(response.statusCode.is2xxSuccessful)
        val body = response.body!!
        assertEquals(false, body.win)
        assertEquals(-50L, body.net)
        assertEquals(950L, body.newBalance)
        assertEquals(null, body.winningSymbol)
    }

    @Test
    fun `scratch returns 400 on insufficient credits`() {
        every { scratchService.scratch(discordId, guildId, 100L) } returns
            ScratchOutcome.InsufficientCredits(stake = 100L, have = 30L)

        val response = controller.scratch(guildId, ScratchRequest(stake = 100L), user)

        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `scratch returns 400 on invalid stake`() {
        every { scratchService.scratch(discordId, guildId, 5L) } returns
            ScratchOutcome.InvalidStake(min = 10L, max = 500L)

        val response = controller.scratch(guildId, ScratchRequest(stake = 5L), user)

        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `scratch rejects with 403 when user is not a member`() {
        every { economyWebService.isMember(discordId, guildId) } returns false

        val response = controller.scratch(guildId, ScratchRequest(stake = 100L), user)

        assertEquals(403, response.statusCode.value())
        verify(exactly = 0) { scratchService.scratch(any(), any(), any()) }
    }

    @Test
    fun `jackpot win surfaces jackpotPayout in the response body`() {
        every { scratchService.scratch(discordId, guildId, 100L) } returns ScratchOutcome.Win(
            stake = 100L, payout = 3_000L, net = 2_900L,
            cells = List(9) { SlotMachine.Symbol.STAR },
            winningSymbol = SlotMachine.Symbol.STAR,
            matchCount = 9,
            newBalance = 8_900L,
            jackpotPayout = 6_000L
        )

        val response = controller.scratch(guildId, ScratchRequest(stake = 100L), user)

        assertEquals(6_000L, response.body!!.jackpotPayout)
    }
}
