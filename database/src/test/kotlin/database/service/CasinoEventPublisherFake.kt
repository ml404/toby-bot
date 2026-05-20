package database.service

import common.events.BaccaratWonEvent
import common.events.CasinoHoldemWonEvent
import common.events.CoinflipWonEvent
import common.events.DiceWonEvent
import common.events.HighlowHandResolvedEvent
import common.events.HorseRacingWonEvent
import common.events.KenoPerfectEvent
import common.events.PlinkoJackpotEvent
import common.events.PokerRoyalFlushEvent
import common.events.RouletteStraightWinEvent
import common.events.ScratchJackpotEvent
import common.events.SlotsJackpotEvent
import common.events.WheelJackpotEvent
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher

/**
 * Test-only `ApplicationEventPublisher` that captures every casino
 * achievement event published during a service call into per-type
 * lists. Each casino game service-test constructs a second service
 * instance with this publisher attached (the default `setup()`
 * instance keeps `eventPublisher = null` so existing tests stay green)
 * and asserts on the captured list afterwards.
 *
 * Mirrors the private `RecordingEventPublisher` pattern already used
 * by `BlackjackServiceTest.kt` (lines 1331-1337). Promoted to its own
 * file because thirteen services would otherwise duplicate the same
 * boilerplate.
 */
class CasinoEventPublisherFake : ApplicationEventPublisher {
    val slotsJackpots: MutableList<SlotsJackpotEvent> = mutableListOf()
    val rouletteStraightWins: MutableList<RouletteStraightWinEvent> = mutableListOf()
    val pokerRoyalFlushes: MutableList<PokerRoyalFlushEvent> = mutableListOf()
    val diceWins: MutableList<DiceWonEvent> = mutableListOf()
    val coinflipWins: MutableList<CoinflipWonEvent> = mutableListOf()
    val kenoPerfects: MutableList<KenoPerfectEvent> = mutableListOf()
    val plinkoJackpots: MutableList<PlinkoJackpotEvent> = mutableListOf()
    val scratchJackpots: MutableList<ScratchJackpotEvent> = mutableListOf()
    val wheelJackpots: MutableList<WheelJackpotEvent> = mutableListOf()
    val horseRacingWins: MutableList<HorseRacingWonEvent> = mutableListOf()
    val baccaratWins: MutableList<BaccaratWonEvent> = mutableListOf()
    val casinoHoldemWins: MutableList<CasinoHoldemWonEvent> = mutableListOf()
    val highlowHandResolutions: MutableList<HighlowHandResolvedEvent> = mutableListOf()

    override fun publishEvent(event: ApplicationEvent) {}

    override fun publishEvent(event: Any) {
        when (event) {
            is SlotsJackpotEvent -> slotsJackpots.add(event)
            is RouletteStraightWinEvent -> rouletteStraightWins.add(event)
            is PokerRoyalFlushEvent -> pokerRoyalFlushes.add(event)
            is DiceWonEvent -> diceWins.add(event)
            is CoinflipWonEvent -> coinflipWins.add(event)
            is KenoPerfectEvent -> kenoPerfects.add(event)
            is PlinkoJackpotEvent -> plinkoJackpots.add(event)
            is ScratchJackpotEvent -> scratchJackpots.add(event)
            is WheelJackpotEvent -> wheelJackpots.add(event)
            is HorseRacingWonEvent -> horseRacingWins.add(event)
            is BaccaratWonEvent -> baccaratWins.add(event)
            is CasinoHoldemWonEvent -> casinoHoldemWins.add(event)
            is HighlowHandResolvedEvent -> highlowHandResolutions.add(event)
        }
    }
}
