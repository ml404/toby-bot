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

@Entity
@Table(name = "poker_hand_log", schema = "public")
@Transactional
class PokerHandLogDto(
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

    /** Comma-separated list of seated players' Discord IDs at hand start. */
    @Column(name = "players", nullable = false, length = 512)
    var players: String = "",

    /** Comma-separated list of winner Discord IDs (one for outright, multiple on chop). */
    @Column(name = "winners", nullable = false, length = 512)
    var winners: String = "",

    @Column(name = "pot", nullable = false)
    var pot: Long = 0,

    @Column(name = "rake", nullable = false)
    var rake: Long = 0,

    /** Compact text rendering of the community board, e.g. "AS,KH,QD,JC,TS". */
    @Column(name = "board", nullable = false, length = 64)
    var board: String = "",

    @Column(name = "resolved_at", nullable = false)
    var resolvedAt: Instant = Instant.now()
) : Serializable
