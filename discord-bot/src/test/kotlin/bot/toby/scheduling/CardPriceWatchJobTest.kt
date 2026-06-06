package bot.toby.scheduling

import bot.toby.command.commands.mtg.ScryfallCubeFetcher
import bot.toby.notify.NotificationDispatch
import bot.toby.notify.NotificationRouter
import common.mtg.CubeCard
import common.notification.NotificationChannelKind
import database.dto.user.CardPriceWatchDto
import database.dto.user.CardPriceWatchDto.Direction
import database.service.user.CardPriceWatchService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CardPriceWatchJobTest {

    private lateinit var watchService: CardPriceWatchService
    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var router: NotificationRouter
    private lateinit var job: CardPriceWatchJob

    @BeforeEach
    fun setUp() {
        watchService = mockk(relaxed = true)
        fetcher = mockk()
        router = mockk(relaxed = true)
        job = CardPriceWatchJob(watchService, fetcher, router)
    }

    private fun watch(id: Long, name: String, dir: Direction, threshold: Double, currency: String = "usd") =
        CardPriceWatchDto(
            id = id, discordId = 7L, guildId = 1L, cardName = name,
            currency = currency, direction = dir.name, threshold = threshold, enabled = true,
        )

    @Test
    fun `fires, DMs and disables a watch whose target is crossed`() {
        every { watchService.listEnabled() } returns listOf(watch(1, "Ragavan", Direction.BELOW, 30.0))
        coEvery { fetcher.fetchByNames(listOf("Ragavan")) } returns
            ScryfallCubeFetcher.Result.Success(listOf(CubeCard("Ragavan", priceUsd = "20.00")))
        every { router.dispatch(any(), any<Long>(), any<Long>(), any<NotificationDispatch.() -> Unit>()) } just runs

        job.checkAll()

        verify(exactly = 1) { router.dispatch(NotificationChannelKind.CARD_PRICE_ALERT, 7L, 1L, any<NotificationDispatch.() -> Unit>()) }
        verify(exactly = 1) { watchService.markFired(1L, any()) }
    }

    @Test
    fun `does not fire while the price stays the wrong side of the threshold`() {
        every { watchService.listEnabled() } returns listOf(watch(1, "Ragavan", Direction.BELOW, 30.0))
        coEvery { fetcher.fetchByNames(any()) } returns
            ScryfallCubeFetcher.Result.Success(listOf(CubeCard("Ragavan", priceUsd = "45.00")))

        job.checkAll()

        verify(exactly = 0) { router.dispatch(any(), any<Long>(), any<Long>(), any<NotificationDispatch.() -> Unit>()) }
        verify(exactly = 0) { watchService.markFired(any(), any()) }
    }

    @Test
    fun `a card with no price in the chosen currency is skipped and left armed`() {
        every { watchService.listEnabled() } returns listOf(watch(1, "Ragavan", Direction.BELOW, 30.0, currency = "eur"))
        coEvery { fetcher.fetchByNames(any()) } returns
            ScryfallCubeFetcher.Result.Success(listOf(CubeCard("Ragavan", priceUsd = "20.00"))) // no EUR price

        job.checkAll()

        verify(exactly = 0) { router.dispatch(any(), any<Long>(), any<Long>(), any<NotificationDispatch.() -> Unit>()) }
        verify(exactly = 0) { watchService.markFired(any(), any()) }
    }

    @Test
    fun `no enabled watches short-circuits without fetching`() {
        every { watchService.listEnabled() } returns emptyList()
        job.checkAll()
        verify(exactly = 0) { router.dispatch(any(), any<Long>(), any<Long>(), any<NotificationDispatch.() -> Unit>()) }
    }

    @Test
    fun `a fetch failure notifies nobody`() {
        every { watchService.listEnabled() } returns listOf(watch(1, "Ragavan", Direction.BELOW, 30.0))
        coEvery { fetcher.fetchByNames(any()) } returns ScryfallCubeFetcher.Result.Failure("Scryfall down")

        job.checkAll()

        verify(exactly = 0) { router.dispatch(any(), any<Long>(), any<Long>(), any<NotificationDispatch.() -> Unit>()) }
    }
}
