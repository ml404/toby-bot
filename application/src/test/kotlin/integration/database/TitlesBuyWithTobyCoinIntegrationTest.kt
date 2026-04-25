package integration.database

import app.Application
import bot.configuration.TestAppConfig
import bot.configuration.TestBotConfig
import bot.configuration.TestManagerConfig
import common.configuration.TestCachingConfig
import database.configuration.TestDatabaseConfig
import database.dto.TitleDto
import database.dto.UserDto
import database.service.EconomyTradeService
import database.service.TitleService
import database.service.TobyCoinMarketService
import database.service.UserService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import web.service.BuyWithTobyOutcome
import web.service.TitlesWebService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import io.mockk.every
import io.mockk.mockk

/**
 * Integration test for the one-click buy-with-TOBY flow. Validates:
 *  - End-to-end flow against H2: market row locked, sell tick appended,
 *    title recorded, credit math balances.
 *  - Two concurrent buy-with-toby calls from the same user for the same
 *    title settle such that exactly one succeeds (owns the title) and the
 *    other surfaces a clean error rather than double-spending.
 */
@SpringBootTest(
    classes = [
        Application::class,
        TestCachingConfig::class,
        TestDatabaseConfig::class,
        TestManagerConfig::class,
        TestAppConfig::class,
        TestBotConfig::class,
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TitlesBuyWithTobyCoinIntegrationTest {

    @Autowired lateinit var titlesWebService: TitlesWebService
    @Autowired lateinit var tradeService: EconomyTradeService
    @Autowired lateinit var marketService: TobyCoinMarketService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var titleService: TitleService
    @Autowired lateinit var jda: JDA
    @PersistenceContext lateinit var em: EntityManager
    @Autowired lateinit var txManager: PlatformTransactionManager

    private val txTemplate: TransactionTemplate by lazy { TransactionTemplate(txManager) }

    private fun insertTitle(label: String, cost: Long): Long =
        txTemplate.execute {
            val dto = TitleDto(label = label, cost = cost)
            em.persist(dto)
            em.flush()
            dto.id!!
        }!!

    companion object {
        private val seq = AtomicLong()
    }

    private data class Fixture(val discordId: Long, val guildId: Long, val titleId: Long)

    private fun newFixture(credits: Long, coins: Long, titleCost: Long): Fixture {
        val id = seq.incrementAndGet()
        val discordId = 800_000L + id
        val guildId = 800_000L + id
        userService.clearCache()
        userService.createNewUser(
            UserDto(discordId, guildId).apply {
                socialCredit = credits
                tobyCoins = coins
            }
        )
        val titleId = insertTitle(label = "T-$id-${System.nanoTime()}", cost = titleCost)
        tradeService.loadOrCreateMarket(guildId)

        val guild: Guild = mockk(relaxed = true)
        val member: Member = mockk(relaxed = true)
        every { jda.getGuildById(guildId) } returns guild
        every { guild.getMemberById(discordId) } returns member
        return Fixture(discordId, guildId, titleId)
    }

    @Test
    fun `pure-TOBY purchase debits coins, credits math balances, title recorded`() {
        val fx = newFixture(credits = 0L, coins = 1_000L, titleCost = 500L)
        val openingPrice = marketService.getMarket(fx.guildId)!!.price

        val outcome = titlesWebService.buyTitleWithTobyCoin(fx.discordId, fx.guildId, fx.titleId)

        assertTrue(outcome is BuyWithTobyOutcome.Ok, "outcome was $outcome")
        val ok = outcome as BuyWithTobyOutcome.Ok
        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertTrue(user.socialCredit!! >= 0L, "credits can't go negative")
        assertEquals(1_000L - ok.soldTobyCoins, user.tobyCoins, "coins match soldTobyCoins")
        assertTrue(titleService.owns(fx.discordId, fx.titleId), "title recorded")
        assertTrue(marketService.getMarket(fx.guildId)!!.price < openingPrice, "sell pushes price down")
    }

    @Test
    fun `no-TOBY-needed path is effectively equivalent to buyTitle`() {
        val fx = newFixture(credits = 1_000L, coins = 50L, titleCost = 100L)

        val outcome = titlesWebService.buyTitleWithTobyCoin(fx.discordId, fx.guildId, fx.titleId)

        assertTrue(outcome is BuyWithTobyOutcome.Ok, "outcome was $outcome")
        val ok = outcome as BuyWithTobyOutcome.Ok
        assertEquals(0L, ok.soldTobyCoins, "no coins needed")
        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertEquals(900L, user.socialCredit)
        assertEquals(50L, user.tobyCoins, "coins untouched")
        assertTrue(titleService.owns(fx.discordId, fx.titleId))
    }

    /**
     * Two concurrent buy-with-toby calls for the same user + title. The
     * pessimistic write-lock chain (user → market → user re-read) must
     * serialise them so that exactly one commits a purchase and the other
     * sees AlreadyOwns (or, in an unlucky split, InsufficientCoins if the
     * first drains the wallet). Never both succeeding, never both failing
     * with a crash.
     */
    @Test
    fun `concurrent buy-with-toby calls cannot double-purchase`() {
        val fx = newFixture(credits = 0L, coins = 400L, titleCost = 500L)

        val executor = Executors.newFixedThreadPool(2)
        val outcomes = try {
            listOf(
                executor.submit<BuyWithTobyOutcome> {
                    titlesWebService.buyTitleWithTobyCoin(fx.discordId, fx.guildId, fx.titleId)
                },
                executor.submit<BuyWithTobyOutcome> {
                    titlesWebService.buyTitleWithTobyCoin(fx.discordId, fx.guildId, fx.titleId)
                }
            ).map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val successes = outcomes.count { it is BuyWithTobyOutcome.Ok }
        assertEquals(1, successes, "exactly one call must succeed; outcomes=$outcomes")
        val loser = outcomes.single { it !is BuyWithTobyOutcome.Ok }
        assertTrue(
            loser is BuyWithTobyOutcome.AlreadyOwns || loser is BuyWithTobyOutcome.InsufficientCoins,
            "losing outcome must be AlreadyOwns or InsufficientCoins but was $loser"
        )

        assertTrue(titleService.owns(fx.discordId, fx.titleId))
        val user = userService.getUserById(fx.discordId, fx.guildId)!!
        assertTrue(user.socialCredit!! >= 0L, "credits stayed non-negative")
        assertTrue(user.tobyCoins >= 0L, "coins stayed non-negative")
    }
}
