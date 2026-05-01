package database.dto

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

/**
 * One side-pot tier from a settled hand. See `V23__poker_hand_pot.sql`
 * for column semantics. Stored unjoined (no FK relation in JPA) — we
 * write rows directly via [database.persistence.PokerHandPotPersistence]
 * and only read them back in the hand-history projection.
 */
@Entity
@Table(name = "poker_hand_pot", schema = "public")
@Transactional
class PokerHandPotDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "hand_log_id", nullable = false)
    var handLogId: Long = 0,

    /** 0 = main pot; 1, 2, ... = increasing side-pot tiers. */
    @Column(name = "tier_index", nullable = false)
    var tierIndex: Int = 0,

    @Column(name = "cap", nullable = false)
    var cap: Long = 0,

    /** Chips in this tier AFTER its share of the rake. */
    @Column(name = "amount", nullable = false)
    var amount: Long = 0,

    /** Comma-separated discord IDs eligible to win this tier. */
    @Column(name = "eligible", nullable = false, length = 512)
    var eligible: String = "",

    /** Comma-separated discord IDs that took the tier. */
    @Column(name = "winners", nullable = false, length = 512)
    var winners: String = "",

    /** `discordId:amount` pairs comma-separated, one per winner. */
    @Column(name = "payouts", nullable = false, length = 512)
    var payouts: String = ""
) : Serializable
