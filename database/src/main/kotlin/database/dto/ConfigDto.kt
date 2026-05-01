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
        POKER_SHOT_CLOCK_SECONDS("POKER_SHOT_CLOCK_SECONDS");
    }

    override fun toString(): String {
        return "ConfigDto{name='$name', value=$value, guildId=$guildId}"
    }
}
