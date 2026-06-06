package bot.toby.scheduling

import bot.toby.command.commands.mtg.ScryfallCubeFetcher
import bot.toby.notify.CardPriceAlertBuilder
import common.logging.DiscordLogger
import common.mtg.CubeCard
import common.mtg.MtgCurrency
import common.notification.NotificationChannelKind
import bot.toby.notify.NotificationRouter
import database.dto.user.CardPriceWatchDto
import database.service.user.CardPriceWatchService
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Periodically checks every enabled [CardPriceWatchDto] against current
 * Scryfall prices and DMs the owner when a watch's threshold is crossed.
 *
 * Card prices are global, so one batched fetch (deduped by name) covers
 * every user's watches. Each fired watch is one-shot: after the DM it's
 * stamped and disabled so an oscillating price can't re-alert. A watch whose
 * card can't be priced (unknown name, or no price in that currency) is left
 * enabled and simply skipped this pass.
 */
@Component
@Profile("prod")
class CardPriceWatchJob @Autowired constructor(
    private val watchService: CardPriceWatchService,
    private val fetcher: ScryfallCubeFetcher,
    private val notificationRouter: NotificationRouter,
) {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT2M")
    fun checkAll() {
        val watches = watchService.listEnabled()
        if (watches.isEmpty()) return

        val byName = fetchPrices(watches.map { it.cardName }.distinct())
        if (byName.isEmpty()) return

        val now = Instant.now()
        watches.forEach { watch ->
            runCatching { evaluate(watch, byName, now) }
                .onFailure { logger.warn("Card price watch ${watch.id} eval failed: ${it.message}") }
        }
    }

    /** Batched, deduped price fetch keyed by lower-cased card name. */
    private fun fetchPrices(names: List<String>): Map<String, CubeCard> =
        runBlocking {
            when (val res = fetcher.fetchByNames(names)) {
                is ScryfallCubeFetcher.Result.Success -> res.cards.associateBy { it.name.lowercase() }
                is ScryfallCubeFetcher.Result.Failure -> {
                    logger.warn("Card price watch price fetch failed: ${res.message}")
                    emptyMap()
                }
            }
        }

    private fun evaluate(watch: CardPriceWatchDto, byName: Map<String, CubeCard>, now: Instant) {
        val card = byName[watch.cardName.lowercase()] ?: return // unknown card — skip, stay armed
        val currency = MtgCurrency.fromCode(watch.currency) ?: run {
            // Corrupt currency on the row — disable so it doesn't churn forever.
            logger.warn("Card price watch ${watch.id} has unknown currency '${watch.currency}'; disabling.")
            watch.id?.let { watchService.markFired(it, now) }
            return
        }
        val price = card.price(currency)?.toDoubleOrNull() ?: return // not priced in this currency — skip
        if (!watch.isTriggeredBy(price)) return

        notificationRouter.dispatch(NotificationChannelKind.CARD_PRICE_ALERT, watch.discordId, watch.guildId) {
            dm { CardPriceAlertBuilder.buildDm(watch, card, currency, price) }
        }
        watch.id?.let { watchService.markFired(it, now) }
    }
}
