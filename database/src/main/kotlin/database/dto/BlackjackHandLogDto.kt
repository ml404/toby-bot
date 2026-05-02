package database.dto

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

/**
 * One settled blackjack hand. Written by
 * [database.service.BlackjackService] in the same transaction as the
 * payout. Mirrors [PokerHandLogDto] but captures blackjack-specific
 * fields (mode = SOLO/MULTI, dealer hand text + total, seat-level
 * result map).
 */
@Entity
@Table(name = "blackjack_hand_log", schema = "public")
@Transactional
class BlackjackHandLogDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "table_id", nullable = false)
    var tableId: Long = 0,

    @Column(name = "hand_number", nullable = false)
    var handNumber: Long = 0,

    /** "SOLO" or "MULTI". */
    @Column(name = "mode", nullable = false, length = 8)
    var mode: String = "",

    /** Comma-separated list of seated players' Discord IDs. */
    @Column(name = "players", nullable = false, length = 512)
    var players: String = "",

    /** Final dealer hand, comma-separated card glyphs (e.g. "K♠,7♥,A♦"). */
    @Column(name = "dealer", nullable = false, length = 64)
    var dealer: String = "",

    @Column(name = "dealer_total", nullable = false)
    var dealerTotal: Int = 0,

    /** Comma-separated `discordId:RESULT` (e.g. "100:PLAYER_WIN,101:PLAYER_BUST"). */
    @Column(name = "seat_results", nullable = false, length = 512)
    var seatResults: String = "",

    /** Comma-separated `discordId:amount`. Empty if no payouts (everyone bust). */
    @Column(name = "payouts", nullable = false, length = 512)
    var payouts: String = "",

    @Column(name = "pot", nullable = false)
    var pot: Long = 0,

    @Column(name = "rake", nullable = false)
    var rake: Long = 0,

    @Column(name = "resolved_at", nullable = false)
    var resolvedAt: Instant = Instant.now()
) : Serializable
