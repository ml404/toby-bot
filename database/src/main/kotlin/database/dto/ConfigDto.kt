package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(name = "ConfigDto.getAll", query = "select a from ConfigDto as a"),
    NamedQuery(name = "ConfigDto.getGuildAll", query = "select a from ConfigDto as a WHERE a.guildId = :guildId "),
    NamedQuery(
        name = "ConfigDto.getValue",
        query = "select a from ConfigDto as a WHERE a.name = :name AND (a.guildId = :guildId OR a.guildId = 'all')"
    )
)
@Entity
@Table(name = "config", schema = "public")
@Transactional
class ConfigDto(
    @Id
    @Column(name = "name")
    var name: String? = null,

    @Column(name = "\"value\"")
    var value: String? = null,

    @Id
    @Column(name = "guild_id")
    var guildId: String? = null
) : Serializable {

    enum class Configurations(val configValue: String) {
        INTRO_VOLUME("DEFAULT_INTRO_VOLUME"),
        VOLUME("DEFAULT_VOLUME"),
        MOVE("DEFAULT_MOVE_CHANNEL"),
        DELETE_DELAY("DELETE_MESSAGE_DELAY"),
        LEADERBOARD_CHANNEL("LEADERBOARD_CHANNEL"),
        ACTIVITY_TRACKING("ACTIVITY_TRACKING"),
        ACTIVITY_TRACKING_NOTIFIED("ACTIVITY_TRACKING_NOTIFIED"),
        // Whole-number percentage (0-50) of every lost casino stake that
        // routes into the per-guild jackpot pool. Defaults to 10 if unset.
        JACKPOT_LOSS_TRIBUTE_PCT("JACKPOT_LOSS_TRIBUTE_PCT"),

        // Decimal percentage (0-50, decimals allowed e.g. 0.5) chance
        // that any casino-game win triggers the jackpot payout. Defaults
        // to 1 if unset. 0 disables jackpot wins entirely.
        JACKPOT_WIN_PCT("JACKPOT_WIN_PCT"),

        // Decimal percentage (0-25, decimals allowed e.g. 0.5) skimmed
        // off every Toby Coin BUY into the per-guild jackpot pool.
        // Defaults to 1 if unset.
        TRADE_BUY_FEE_PCT("TRADE_BUY_FEE_PCT"),

        // Decimal percentage (0-25, decimals allowed e.g. 0.5) skimmed
        // off every Toby Coin SELL into the per-guild jackpot pool.
        // Defaults to 1 if unset.
        TRADE_SELL_FEE_PCT("TRADE_SELL_FEE_PCT"),

        // Whole-number percentage (0-20) of every settled poker pot that
        // routes into the per-guild jackpot pool. Defaults to 5 if unset.
        POKER_RAKE_PCT("POKER_RAKE_PCT"),

        // v2 (PR #v2-2): per-guild poker table parameters. All defaults
        // come from `PokerService.companion` constants if unset; admins
        // can tune the limits via /setconfig (Discord) or the
        // /moderation web tab. Each table snapshots these values at
        // creation time so a mid-hand admin tweak doesn't break
        // invariants for the in-flight game.
        POKER_SMALL_BLIND("POKER_SMALL_BLIND"),
        POKER_BIG_BLIND("POKER_BIG_BLIND"),
        POKER_SMALL_BET("POKER_SMALL_BET"),
        POKER_BIG_BET("POKER_BIG_BET"),
        POKER_MIN_BUY_IN("POKER_MIN_BUY_IN"),
        POKER_MAX_BUY_IN("POKER_MAX_BUY_IN"),
        // 2-9 (Texas Hold'em practical max). Smaller values just cap a
        // table early; existing seats are not evicted on shrink.
        POKER_MAX_SEATS("POKER_MAX_SEATS"),
        // Per-actor decision deadline in seconds. 0 disables the clock
        // entirely; otherwise the table registry auto-folds whoever is
        // up if they don't act in time. Default 30s when unset.
        POKER_SHOT_CLOCK_SECONDS("POKER_SHOT_CLOCK_SECONDS"),

        // Per-guild blackjack table parameters. All defaults come from
        // `Blackjack.companion` constants if unset. Each table snapshots
        // these values at creation time so a mid-hand admin tweak doesn't
        // break invariants for the in-flight hand.
        // Whole-number percentage (0-20) of the multi-table losers' pool
        // routed to the jackpot pool. Default 5 if unset.
        BLACKJACK_RAKE_PCT("BLACKJACK_RAKE_PCT"),
        // Min/max ante per multi hand (also bounds for /blackjack solo
        // stakes). Defaults to 10 / 500.
        BLACKJACK_MIN_ANTE("BLACKJACK_MIN_ANTE"),
        BLACKJACK_MAX_ANTE("BLACKJACK_MAX_ANTE"),
        // 2-7 seats per multi table. Default 5.
        BLACKJACK_MAX_SEATS("BLACKJACK_MAX_SEATS"),
        // Per-actor shot clock for multi tables. Default 30s; 0 disables.
        BLACKJACK_SHOT_CLOCK_SECONDS("BLACKJACK_SHOT_CLOCK_SECONDS"),
        // "true" = dealer hits soft 17 (H17, slightly worse for player).
        // Anything else = stands on all 17 (S17, default).
        BLACKJACK_DEALER_HITS_SOFT_17("BLACKJACK_DEALER_HITS_SOFT_17"),
        // Natural-blackjack payout numerator/denominator. Defaults to
        // 3/2 (i.e. classic 3:2 payout). Set to 6/5 for a stingier room
        // (typical Vegas low-limit table). Total payout multiplier =
        // 1 + (num/den), e.g. 3:2 → 2.5×, 6:5 → 2.2×.
        BLACKJACK_BJ_PAYOUT_NUM("BLACKJACK_BJ_PAYOUT_NUM"),
        BLACKJACK_BJ_PAYOUT_DEN("BLACKJACK_BJ_PAYOUT_DEN"),

        // Whole-number social credits granted to every known user in the
        // guild once per UTC day, regardless of voice activity. 0 (or unset)
        // disables UBI. Bypasses DAILY_CREDIT_CAP — the whole point is to
        // let non-voice users participate in casino/coin features.
        UBI_DAILY_AMOUNT("UBI_DAILY_AMOUNT"),

        // Per-guild override of the social-credit daily cap that applies to
        // voice, command, intro, and UI-trade earnings. Falls back to 90
        // (SocialCreditAwardService.DEFAULT_DAILY_CAP) when unset or invalid.
        DAILY_CREDIT_CAP("DAILY_CREDIT_CAP");
    }

    override fun toString(): String {
        return "ConfigDto{name='$name', value=$value, guildId=$guildId}"
    }
}
