package web.service

import database.dto.guild.ConfigDto
import database.dto.lottery.JackpotLotteryDto
import database.service.guild.ConfigService
import database.service.lottery.JackpotLotteryService
import database.service.guild.TitleService
import database.service.user.UserService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Snapshot-wiring coverage. The view-model derivation
 * (`LotteryViewModel.from`) is already covered by `LotteryControllerTest`;
 * here we pin that the snapshot itself surfaces the right
 * `weightedIncentives` payload depending on whether a weighted lottery
 * is open and whether tiers are configured for the guild. Tier
 * filtering / clamping is the responsibility of `LotteryHelper` and
 * is covered by `LotteryHelperTest`.
 */
class LotteryWebServiceTest {

    private val guildId = 100L
    private val discordId = 1L

    private lateinit var jackpotLotteryService: JackpotLotteryService
    private lateinit var configService: ConfigService
    private lateinit var memberLookupHelper: MemberLookupHelper
    private lateinit var userService: UserService
    private lateinit var titleService: TitleService
    private lateinit var service: LotteryWebService

    @BeforeEach
    fun setup() {
        jackpotLotteryService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        memberLookupHelper = mockk(relaxed = true)
        userService = mockk(relaxed = true)
        titleService = mockk(relaxed = true)
        service = LotteryWebService(
            jackpotLotteryService = jackpotLotteryService,
            configService = configService,
            memberLookupHelper = memberLookupHelper,
            userService = userService,
            titleService = titleService,
        )
    }

    private fun stubTier(key: ConfigDto.Configurations, value: String) {
        every {
            configService.getConfigByName(key.configValue, guildId.toString())
        } returns ConfigDto(name = key.configValue, value = value, guildId = guildId.toString())
    }

    @Test
    fun `snapshot returns empty incentives when no weighted lottery is open`() {
        // No open weighted lottery → no point resolving tiers; the
        // template should never render the panel anyway, and we save
        // three config-table lookups per snapshot.
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns null

        val snap = service.snapshot(guildId, discordId)

        assertTrue(snap.weightedIncentives.bulkTiers.isEmpty())
        assertTrue(snap.weightedIncentives.multiplierTiers.isEmpty())
        assertTrue(snap.weightedIncentives.poolMilestones.isEmpty())
        assertTrue(snap.weightedIncentives.isEmpty)
    }

    @Test
    fun `snapshot returns empty incentives when a weighted lottery is open but no tiers are configured`() {
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )

        val snap = service.snapshot(guildId, discordId)

        // Default state — no tiers stubbed, getter returns null
        // → LotteryHelper returns empty lists.
        assertTrue(snap.weightedIncentives.isEmpty)
    }

    @Test
    fun `snapshot surfaces only the active bulk tier when configured`() {
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        stubTier(ConfigDto.Configurations.LOTTERY_BULK_TIER1_BUY, "10")
        stubTier(ConfigDto.Configurations.LOTTERY_BULK_TIER1_BONUS, "3")
        // Tier 2 / 3 left unconfigured — should not appear.

        val snap = service.snapshot(guildId, discordId)

        assertEquals(1, snap.weightedIncentives.bulkTiers.size)
        assertEquals(10L, snap.weightedIncentives.bulkTiers.first().buy)
        assertEquals(3L, snap.weightedIncentives.bulkTiers.first().bonus)
        assertTrue(snap.weightedIncentives.multiplierTiers.isEmpty())
        assertTrue(snap.weightedIncentives.poolMilestones.isEmpty())
    }

    @Test
    fun `snapshot surfaces milestones when configured for an open weighted lottery`() {
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        stubTier(ConfigDto.Configurations.LOTTERY_MILESTONE1_TICKETS, "50")
        stubTier(ConfigDto.Configurations.LOTTERY_MILESTONE1_PCT, "10")
        stubTier(ConfigDto.Configurations.LOTTERY_MILESTONE2_TICKETS, "100")
        stubTier(ConfigDto.Configurations.LOTTERY_MILESTONE2_PCT, "5")

        val snap = service.snapshot(guildId, discordId)

        assertEquals(2, snap.weightedIncentives.poolMilestones.size)
        assertEquals(50L, snap.weightedIncentives.poolMilestones.first().tickets)
        assertEquals(10L, snap.weightedIncentives.poolMilestones.first().pct)
    }

    @Test
    fun `snapshot ranks top holders by ticketCount + bonusTickets so big-bonus buyers don't fall off the leaderboard`() {
        // Three buyers: A holds 20 paid, B holds 10 paid + 15 bonus
        // (effective 25), C holds 15 paid. Without the bonus-aware
        // sort, B ranks third (10 < 15 < 20). With it, B is first.
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every { jackpotLotteryService.ticketsForOpenWeighted(guildId) } returns listOf(
            database.dto.lottery.JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = 1L, ticketCount = 20, spent = 2_000L,
                bonusTickets = 0L,
            ),
            database.dto.lottery.JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = 2L, ticketCount = 10, spent = 1_000L,
                bonusTickets = 15L,
            ),
            database.dto.lottery.JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = 3L, ticketCount = 15, spent = 1_500L,
                bonusTickets = 0L,
            ),
        )

        val snap = service.snapshot(guildId, discordId)

        val ranked = snap.weightedTopHolders.map { it.discordId }
        assertEquals(listOf(2L, 1L, 3L), ranked, "effective weights: 25, 20, 15")
    }

    @Test
    fun `snapshot TopHolder carries bonusTickets through to the view model`() {
        every { jackpotLotteryService.getOpenWeighted(guildId) } returns JackpotLotteryDto(
            id = 1L, guildId = guildId,
            status = JackpotLotteryDto.STATUS_OPEN,
            mode = JackpotLotteryDto.MODE_TICKET_WEIGHTED,
        )
        every { jackpotLotteryService.ticketsForOpenWeighted(guildId) } returns listOf(
            database.dto.lottery.JackpotLotteryTicketDto(
                lotteryId = 1L, discordId = 7L, ticketCount = 10, spent = 1_000L,
                bonusTickets = 3L,
            ),
        )

        val snap = service.snapshot(guildId, discordId)

        val holder = snap.weightedTopHolders.single()
        assertEquals(10, holder.ticketCount)
        assertEquals(3L, holder.bonusTickets)
    }
}
