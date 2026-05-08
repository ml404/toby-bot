package database.service

import database.card.Card
import database.card.Rank
import database.card.Suit
import database.dto.UserDto
import database.economy.Baccarat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BaccaratServiceTest {

    private lateinit var userService: UserService
    private lateinit var jackpotService: JackpotService
    private lateinit var tradeService: EconomyTradeService
    private lateinit var marketService: TobyCoinMarketService
    private lateinit var configService: ConfigService
    private lateinit var baccarat: Baccarat
    private lateinit var service: BaccaratService

    private val discordId = 100L
    private val guildId = 200L

    @BeforeEach
    fun setup() {
        userService = mockk(relaxed = true)
        jackpotService = mockk(relaxed = true)
        tradeService = mockk(relaxed = true)
        marketService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        baccarat = mockk(relaxed = true)
        service = BaccaratService(
            userService, jackpotService, tradeService, marketService, configService,
            baccarat, Random(0)
        )
    }

    private fun userWithBalance(balance: Long): UserDto =
        UserDto(discordId, guildId).apply { socialCredit = balance }

    private fun playerCards() = listOf(Card(Rank.FIVE, Suit.SPADES), Card(Rank.THREE, Suit.HEARTS))
    private fun bankerCards() = listOf(Card(Rank.TWO, Suit.CLUBS), Card(Rank.FOUR, Suit.DIAMONDS))

    private fun handResult(
        side: Baccarat.Side,
        winner: Baccarat.Side,
        multiplier: Double
    ): Baccarat.Hand = Baccarat.Hand(
        side = side,
        winner = winner,
        playerCards = playerCards(),
        bankerCards = bankerCards(),
        playerTotal = 8,
        bankerTotal = 6,
        isPlayerNatural = true,
        isBankerNatural = false,
        multiplier = multiplier
    )

    @Test
    fun `Player win debits stake and credits payout atomically`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { baccarat.play(Baccarat.Side.PLAYER, any()) } returns handResult(
            side = Baccarat.Side.PLAYER, winner = Baccarat.Side.PLAYER, multiplier = 2.0
        )
        val captured = slot<UserDto>()
        every { userService.updateUser(capture(captured)) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, side = Baccarat.Side.PLAYER)

        val win = assertInstanceOf(BaccaratService.PlayOutcome.Win::class.java, outcome)
        assertEquals(100L, win.stake)
        assertEquals(200L, win.payout)
        assertEquals(100L, win.net)
        assertEquals(2.0, win.multiplier, 1e-9)
        assertEquals(1_100L, win.newBalance)
        assertEquals(Baccarat.Side.PLAYER, win.side)
        assertEquals(Baccarat.Side.PLAYER, win.winner)
        assertEquals(1_100L, captured.captured.socialCredit)
    }

    @Test
    fun `Banker win pays out at the 5pct-commission multiplier`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { baccarat.play(Baccarat.Side.BANKER, any()) } returns handResult(
            side = Baccarat.Side.BANKER, winner = Baccarat.Side.BANKER, multiplier = 1.95
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, side = Baccarat.Side.BANKER)

        val win = assertInstanceOf(BaccaratService.PlayOutcome.Win::class.java, outcome)
        // 100 × 1.95 = 195 payout, 95 net
        assertEquals(195L, win.payout)
        assertEquals(95L, win.net)
        assertEquals(1.95, win.multiplier, 1e-9)
        assertEquals(1_095L, win.newBalance)
    }

    @Test
    fun `loss debits stake only`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { baccarat.play(Baccarat.Side.PLAYER, any()) } returns handResult(
            side = Baccarat.Side.PLAYER, winner = Baccarat.Side.BANKER, multiplier = 0.0
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, side = Baccarat.Side.PLAYER)

        val lose = assertInstanceOf(BaccaratService.PlayOutcome.Lose::class.java, outcome)
        assertEquals(100L, lose.stake)
        assertEquals(400L, lose.newBalance)
        assertEquals(Baccarat.Side.BANKER, lose.winner)
    }

    @Test
    fun `tied game pushes a Player side bet — stake is refunded`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { baccarat.play(Baccarat.Side.PLAYER, any()) } returns handResult(
            side = Baccarat.Side.PLAYER, winner = Baccarat.Side.TIE, multiplier = 1.0
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, side = Baccarat.Side.PLAYER)

        val push = assertInstanceOf(BaccaratService.PlayOutcome.Push::class.java, outcome)
        assertEquals(100L, push.stake)
        // Push: 100×1 - 100 = net 0, balance unchanged.
        assertEquals(500L, push.newBalance)
        // No jackpot tribute on a push.
        verify(exactly = 0) { jackpotService.addToPool(any(), any()) }
    }

    @Test
    fun `Tie side bet wins at the natural multiplier on a tied game`() {
        val user = userWithBalance(1_000L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { baccarat.play(Baccarat.Side.TIE, any()) } returns handResult(
            side = Baccarat.Side.TIE, winner = Baccarat.Side.TIE, multiplier = 9.0
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.play(discordId, guildId, stake = 50L, side = Baccarat.Side.TIE)

        val win = assertInstanceOf(BaccaratService.PlayOutcome.Win::class.java, outcome)
        assertEquals(450L, win.payout)
        assertEquals(400L, win.net)
        assertEquals(1_400L, win.newBalance)
    }

    @Test
    fun `insufficient credits is rejected without rolling the hand`() {
        val user = userWithBalance(50L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, side = Baccarat.Side.PLAYER)

        assertInstanceOf(BaccaratService.PlayOutcome.InsufficientCredits::class.java, outcome)
        verify(exactly = 0) { userService.updateUser(any()) }
        verify(exactly = 0) { baccarat.play(any(), any()) }
    }

    @Test
    fun `invalid stake is rejected before locking the user`() {
        val outcome = service.play(
            discordId, guildId, stake = Baccarat.MIN_STAKE - 1, side = Baccarat.Side.PLAYER
        )

        assertInstanceOf(BaccaratService.PlayOutcome.InvalidStake::class.java, outcome)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
        verify(exactly = 0) { baccarat.play(any(), any()) }
    }

    @Test
    fun `unknown user is rejected`() {
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns null

        val outcome = service.play(discordId, guildId, stake = 100L, side = Baccarat.Side.PLAYER)

        assertEquals(BaccaratService.PlayOutcome.UnknownUser, outcome)
    }

    @Test
    fun `loss tributes 10 percent of the stake into the jackpot pool`() {
        val user = userWithBalance(500L)
        every { userService.getUserByIdForUpdate(discordId, guildId) } returns user
        every { baccarat.play(Baccarat.Side.PLAYER, any()) } returns handResult(
            side = Baccarat.Side.PLAYER, winner = Baccarat.Side.BANKER, multiplier = 0.0
        )
        every { userService.updateUser(any()) } returns user

        val outcome = service.play(discordId, guildId, stake = 100L, side = Baccarat.Side.PLAYER)

        val lose = assertInstanceOf(BaccaratService.PlayOutcome.Lose::class.java, outcome)
        assertEquals(10L, lose.lossTribute)
        verify(exactly = 1) { jackpotService.addToPool(guildId, 10L) }
    }

    @Test
    fun `previewMultiplier delegates to the underlying logic and does not touch the user`() {
        every { baccarat.previewMultiplier(Baccarat.Side.BANKER) } returns 1.95

        val multiplier = service.previewMultiplier(Baccarat.Side.BANKER)

        assertEquals(1.95, multiplier, 1e-9)
        verify(exactly = 0) { userService.getUserByIdForUpdate(any(), any()) }
        verify(exactly = 0) { userService.updateUser(any()) }
    }
}
