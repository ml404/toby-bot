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
        DAILY_CREDIT_CAP("DAILY_CREDIT_CAP"),

        // Per-game min/max stake bounds. Defaults come from each game's
        // companion-object MIN_STAKE/MAX_STAKE constants when unset, so
        // guilds that never touch these keys see today's behaviour.
        // No upper ceiling — admins can raise max arbitrarily; jackpot
        // probability scaling is anchored to JACKPOT_STAKE_ANCHOR rather
        // than per-game maxStake so it stays sensible at any cap.
        DICE_MIN_STAKE("DICE_MIN_STAKE"),
        DICE_MAX_STAKE("DICE_MAX_STAKE"),
        COINFLIP_MIN_STAKE("COINFLIP_MIN_STAKE"),
        COINFLIP_MAX_STAKE("COINFLIP_MAX_STAKE"),
        SLOTS_MIN_STAKE("SLOTS_MIN_STAKE"),
        SLOTS_MAX_STAKE("SLOTS_MAX_STAKE"),
        HIGHLOW_MIN_STAKE("HIGHLOW_MIN_STAKE"),
        HIGHLOW_MAX_STAKE("HIGHLOW_MAX_STAKE"),
        BACCARAT_MIN_STAKE("BACCARAT_MIN_STAKE"),
        BACCARAT_MAX_STAKE("BACCARAT_MAX_STAKE"),
        KENO_MIN_STAKE("KENO_MIN_STAKE"),
        KENO_MAX_STAKE("KENO_MAX_STAKE"),
        SCRATCH_MIN_STAKE("SCRATCH_MIN_STAKE"),
        SCRATCH_MAX_STAKE("SCRATCH_MAX_STAKE"),
        ROULETTE_MIN_STAKE("ROULETTE_MIN_STAKE"),
        ROULETTE_MAX_STAKE("ROULETTE_MAX_STAKE"),
        // Solo blackjack reuses the existing BLACKJACK_MIN_ANTE /
        // BLACKJACK_MAX_ANTE keys — solo and multi share a single
        // configurable stake range.
        HOLDEM_MIN_STAKE("HOLDEM_MIN_STAKE"),
        HOLDEM_MAX_STAKE("HOLDEM_MAX_STAKE"),
        DUEL_MIN_STAKE("DUEL_MIN_STAKE"),
        DUEL_MAX_STAKE("DUEL_MAX_STAKE"),

        // Reference stake size for jackpot probability scaling. Bets at
        // or above this anchor roll at the full JACKPOT_WIN_PCT base
        // probability; smaller bets scale linearly as stake/anchor.
        // Decoupled from per-game max stake so admins can raise max
        // freely without shrinking jackpot odds. Default 500.
        JACKPOT_STAKE_ANCHOR("JACKPOT_STAKE_ANCHOR"),

        // Whole-number percentage (1-100) of the per-guild jackpot pool
        // paid out on a winning roll. Defaults to 100 when unset (current
        // behaviour: winner banks the entire pool). Set to e.g. 30 so a
        // single roll never sweeps the whole pool — the remainder stays
        // in and re-seeds the next cycle, preventing one lucky bet from
        // unbalancing the server economy.
        JACKPOT_PAYOUT_PCT("JACKPOT_PAYOUT_PCT"),

        // Whole-number days a prior jackpot winner is ineligible for
        // another payout. 0 (default) disables the cooldown; recommended
        // value 14. Applies to both casino-roll wins and lottery draw
        // wins so the same player can't sweep both within the window.
        JACKPOT_WINNER_COOLDOWN_DAYS("JACKPOT_WINNER_COOLDOWN_DAYS"),

        // Whole-number trailing-day window over which the jackpot
        // eligibility check counts the user's distinct activity days.
        // 0 (default) disables the gate (every user is eligible);
        // recommended 7. Activity is sourced from voice_credit_daily —
        // any day with credit-cap-eligible earnings counts.
        JACKPOT_ACTIVITY_WINDOW_DAYS("JACKPOT_ACTIVITY_WINDOW_DAYS"),

        // Whole-number minimum count of distinct activity days within
        // JACKPOT_ACTIVITY_WINDOW_DAYS required for the user to be
        // jackpot-eligible. Defaults to 1; recommended 3 paired with a
        // 7-day window. Ignored when the window is 0.
        JACKPOT_ACTIVITY_MIN_DAYS("JACKPOT_ACTIVITY_MIN_DAYS"),

        // Whole-number ceiling (0-100) on a game's canonical RTP for it
        // to roll for the jackpot. 0 (default) disables the gate so every
        // game stays eligible (pre-PR behaviour). Recommended 95: blocks
        // Coinflip (1.0), Blackjack (~0.99), Baccarat (~0.99), Roulette
        // (0.973) — games that already pay back ~all stake on their own
        // — while keeping Slots, Scratch, Dice, Keno, and HighLow
        // eligible. The intent is "high-RTP games don't *also* need a
        // jackpot sweetener; jackpots compensate for house edge." A
        // failed gate looks identical to a missed roll: no payout, pool
        // keeps growing, no exception surfaced.
        JACKPOT_RTP_MAX_PCT("JACKPOT_RTP_MAX_PCT"),

        // Whole-number percentage (0-50) — ceiling on the dynamic house
        // edge applied to web casino bets that match an autoclicker
        // signature (same screen pixel ± 2px clicked repeatedly with no
        // intervening mouse motion). Each consecutive bot-like click
        // adds ~2.5 pp to the edge; the cap saturates near streak 12 at
        // the default 30. Set to 0 to disable the gate for that game.
        // Discord call paths are unaffected — the gate only applies
        // when the frontend supplies click coords.
        //
        // Per game so admins can tune sensitivity differently across
        // games with different baseline RTPs.
        COINFLIP_BOT_EDGE_MAX_PCT("COINFLIP_BOT_EDGE_MAX_PCT"),
        DICE_BOT_EDGE_MAX_PCT("DICE_BOT_EDGE_MAX_PCT"),
        SLOTS_BOT_EDGE_MAX_PCT("SLOTS_BOT_EDGE_MAX_PCT"),

        // Per-guild text-channel ID where anti-autoclicker session embeds
        // are posted: one message per suspicion session, edited in place
        // as forced-loss substitutions accumulate, finalised when the
        // streak resets. Stored as a stringified Long. Falls back to the
        // guild's system channel when unset, unparseable, or the bot has
        // no permission on the configured channel.
        CASINO_MODLOG_CHANNEL_ID("CASINO_MODLOG_CHANNEL_ID"),

        // Daily match-numbers lottery (Pick 5 of 1-49) auto-draw toggle.
        // Boolean ("true"/"false"); defaults to "false" so guilds opt in.
        // When enabled, LotteryDailyJob runs at 00:00 UTC: closes the
        // previous day's draw if any, opens a fresh one seeded from the
        // jackpot pool. Doubles as a credit sink — a slice of every
        // ticket sale routes back to the jackpot.
        LOTTERY_DAILY_ENABLED("LOTTERY_DAILY_ENABLED"),

        // Whole-number credits charged per match-numbers ticket. Default
        // 50. Range 1-1_000_000. Tickets debit `count × ticket_price`
        // from the buyer at submission time.
        LOTTERY_DAILY_TICKET_PRICE("LOTTERY_DAILY_TICKET_PRICE"),

        // Whole-number percentage (1-100) of the live jackpot pool that
        // seeds each day's prize pool at open. Default 5. Lower = slower
        // drain; higher = aggressive drain. Combined with ticket
        // revenue, this is the prize budget for that day's draw.
        LOTTERY_DAILY_SEED_PCT("LOTTERY_DAILY_SEED_PCT"),

        // Whole-number percentage (0-100) of every match-numbers ticket
        // sale routed to the per-guild jackpot pool; the remainder feeds
        // the day's prize pool. Default 30. The 30/70 split makes the
        // daily lottery a credit sink while keeping engagement-driven
        // prize growth healthy.
        LOTTERY_DAILY_REVENUE_JACKPOT_PCT("LOTTERY_DAILY_REVENUE_JACKPOT_PCT"),

        // Which game mode the daily auto-draw uses. Two values:
        //   "NUMBER_MATCH" — Pick 5 of 1-49 lotto-style. Best for
        //                    high-engagement servers (≥30 tickets/day);
        //                    relies on tier hit-rates to drain the pool.
        //   "WEIGHTED"     — Top-3 weighted draw (50/30/20). Best for
        //                    low-engagement servers (<30 tickets/day);
        //                    every draw guarantees a winner, predictable
        //                    drain regardless of ticket volume.
        // Defaults to "NUMBER_MATCH" so existing guilds aren't disrupted.
        LOTTERY_DAILY_MODE("LOTTERY_DAILY_MODE"),

        // Whole-number minimum *distinct* buyers required for the daily
        // draw to actually pay out. Below this count, the draw cancels:
        // every buyer is refunded and the seed returns to the jackpot
        // pool. Default 2 — prevents a single user sweeping the seeded
        // pool when they're the only one who engaged. Range [1, 50];
        // 1 disables the safeguard (matches pre-PR behaviour).
        LOTTERY_DAILY_MIN_BUYERS("LOTTERY_DAILY_MIN_BUYERS"),

        // Discord text-channel id where the daily lottery announcer
        // posts the close+open summary (and pings winners). Optional —
        // when blank/unset the announcer falls back to LEADERBOARD_CHANNEL,
        // then the guild's system channel. Resolution mirrors the
        // monthly leaderboard job's pattern.
        LOTTERY_CHANNEL("LOTTERY_CHANNEL"),

        // Wide-ping mode for the daily lottery announcement. One of
        // "OFF" (no wide ping — winners still pinged), "HERE" (@here —
        // online members only), or "EVERYONE" (@everyone — all members).
        // Defaults to "EVERYONE" so a fresh-install guild gets the most
        // visible nudge to buy tickets; admins can dial it down per-server.
        LOTTERY_PING_MODE("LOTTERY_PING_MODE");
    }

    override fun toString(): String {
        return "ConfigDto{name='$name', value=$value, guildId=$guildId}"
    }
}
