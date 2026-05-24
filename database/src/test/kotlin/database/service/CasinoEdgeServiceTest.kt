package database.service

import common.events.AntiAutoclickEvent
import database.dto.ConfigDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import kotlin.random.Random
import database.service.casino.CasinoBotSuspicionService
import database.service.casino.CasinoEdgeService
import database.service.guild.ConfigService

class CasinoEdgeServiceTest {

    private lateinit var botSuspicionService: CasinoBotSuspicionService
    private lateinit var configService: ConfigService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: CasinoEdgeService

    private val discordId = 100L
    private val guildId = 200L
    private val gameKey = "coinflip"
    private val edgeMaxConfig = ConfigDto.Configurations.COINFLIP_BOT_EDGE_MAX_PCT

    @BeforeEach
    fun setup() {
        botSuspicionService = mockk(relaxed = true)
        configService = mockk(relaxed = true)
        eventPublisher = mockk(relaxed = true)
        // relaxed=true returns 0 for Int — keeps the gate dormant unless a
        // test stubs a higher streak explicitly.
        every { botSuspicionService.recordAndScore(any(), any(), any(), any(), any(), any()) } returns 0
    }

    private fun stubEdgeMaxConfig(value: String?) {
        every {
            configService.getConfigByName(edgeMaxConfig.configValue, guildId.toString())
        } returns value?.let { ConfigDto(name = "x", value = it, guildId = guildId.toString()) }
    }

    private fun stubStreak(streak: Int) {
        every {
            botSuspicionService.recordAndScore(discordId, guildId, gameKey, any(), any(), any())
        } returns streak
    }

    @Test
    fun `streak 0 returns the fair outcome unchanged (no roll)`() {
        // No suspicion, no bias — the fair outcome flows through verbatim.
        // Random isn't even consulted (defensive: a misuse that boosted a
        // 0-streak bet would be a bug).
        stubStreak(0)
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, mockk())

        val result = service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )

        assertEquals("fair", result)
    }

    @Test
    fun `streak above 0 with a deterministic random below the edge substitutes the loss`() {
        // streak=12 × 2.5pp = 30 % edge at the default cap; random=0.0 is
        // strictly below 0.30, so the substitution fires.
        stubStreak(12)
        val random = mockk<Random>()
        every { random.nextDouble() } returns 0.0
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, random)

        val result = service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )

        assertEquals("loss", result)
    }

    @Test
    fun `streak above 0 with a deterministic random above the edge keeps the fair outcome`() {
        // streak=12 × 2.5pp = 30 % edge at the default cap; random=0.999 is
        // above 0.30, so the fair outcome wins.
        stubStreak(12)
        val random = mockk<Random>()
        every { random.nextDouble() } returns 0.999
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, random)

        val result = service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )

        assertEquals("fair", result)
    }

    @Test
    fun `cap of 0 disables the gate even at very high streak`() {
        // Admin override → no substitutions ever happen, regardless of streak.
        stubEdgeMaxConfig("0")
        stubStreak(100)
        val random = mockk<Random>()
        every { random.nextDouble() } returns 0.0
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, random)

        val result = service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )

        assertEquals("fair", result, "cap=0 must keep behaviour fair")
    }

    @Test
    fun `admin override of 10 percent caps the edge at that value`() {
        // streak=50 × 2.5pp = 125 % raw edge, clamped to 10 % by admin override.
        stubEdgeMaxConfig("10")
        stubStreak(50)
        val random = mockk<Random>()
        // 0.099 < 0.10 → substitutes. 0.11 > 0.10 → fair.
        every { random.nextDouble() } returns 0.099
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, random)

        val resultJustUnder = service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )
        assertEquals("loss", resultJustUnder)

        // Now stub the random to be just over the cap.
        every { random.nextDouble() } returns 0.11
        val resultJustOver = service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )
        assertEquals("fair", resultJustOver, "0.11 above the 10 % cap → no substitution")
    }

    @Test
    fun `admin override above 50 percent is clamped at the hard ceiling`() {
        // 90 % attempted, 50 % enforced. random = 0.49 < 0.50 substitutes;
        // random = 0.51 falls through.
        stubEdgeMaxConfig("90")
        stubStreak(50)
        val random = mockk<Random>()
        every { random.nextDouble() } returns 0.49
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, random)

        val withinCeiling = service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )
        assertEquals("loss", withinCeiling)

        every { random.nextDouble() } returns 0.51
        val aboveCeiling = service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )
        assertEquals("fair", aboveCeiling, "boost is clamped at 50 % even with a 90 % admin override")
    }

    @Test
    fun `applyBotEdge across many runs produces RTP near (1 - cap) at saturated streak`() {
        // Empirical sanity: at the default 30 % cap with streak=12, the
        // substitution fires roughly 30 % of the time. n=10k with a fixed
        // seed gives a tight enough sample that ±5pp is comfortable.
        stubStreak(12)
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, Random(2026))

        var fairCount = 0
        repeat(10_000) {
            val result = service.applyBotEdge(
                discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
                fairOutcome = "fair", asLoss = { "loss" },
            )
            if (result == "fair") fairCount++
        }
        val fairRate = fairCount / 10_000.0
        // Expected ~70 % fair / ~30 % substituted.
        assertNotEquals(true, fairRate < 0.65 || fairRate > 0.75,
            "expected ~70 % fair (±5pp) at 30 % cap with streak=12, saw $fairRate")
    }

    // -------- BiasFired event publishing --------

    @Test
    fun `publishes BiasFired when substitution fires`() {
        // streak=12 × 2.5pp = 30 % edge; random=0.0 < 0.30 → fires the
        // substitution AND should publish a BiasFired event with the
        // streak and effective edge%.
        stubStreak(12)
        val random = mockk<Random>()
        every { random.nextDouble() } returns 0.0
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, random)

        service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.BiasFired(guildId, discordId, gameKey, streak = 12, edgePct = 30.0)
            )
        }
    }

    @Test
    fun `does not publish BiasFired when streak is 0`() {
        // No suspicion → no event, regardless of random draw.
        stubStreak(0)
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, mockk(relaxed = true))

        service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )

        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.BiasFired>()) }
    }

    @Test
    fun `does not publish BiasFired when random draw exceeds edge`() {
        // Streak is suspect, but the random roll lands above the edge —
        // the fair outcome stands and no event fires.
        stubStreak(12)
        val random = mockk<Random>()
        every { random.nextDouble() } returns 0.999
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, random)

        service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )

        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.BiasFired>()) }
    }

    @Test
    fun `does not publish BiasFired when admin cap is 0`() {
        // Cap=0 disables the gate entirely — substitution can never fire,
        // so no event is published either.
        stubEdgeMaxConfig("0")
        stubStreak(100)
        val random = mockk<Random>()
        every { random.nextDouble() } returns 0.0
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, random)

        service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )

        verify(exactly = 0) { eventPublisher.publishEvent(ofType<AntiAutoclickEvent.BiasFired>()) }
    }

    @Test
    fun `BiasFired edgePct reflects the post-cap effective house edge`() {
        // streak=50 raw → 125 % edge, capped to 10 % by admin override —
        // BiasFired should report 10.0, not 125.0.
        stubEdgeMaxConfig("10")
        stubStreak(50)
        val random = mockk<Random>()
        every { random.nextDouble() } returns 0.05
        service = CasinoEdgeService(botSuspicionService, configService, eventPublisher, random)

        service.applyBotEdge(
            discordId, guildId, gameKey, 100, 200, false, edgeMaxConfig,
            fairOutcome = "fair", asLoss = { "loss" },
        )

        verify(exactly = 1) {
            eventPublisher.publishEvent(
                AntiAutoclickEvent.BiasFired(guildId, discordId, gameKey, streak = 50, edgePct = 10.0)
            )
        }
    }
}
