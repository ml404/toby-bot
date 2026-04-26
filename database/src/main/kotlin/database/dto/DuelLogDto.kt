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
@Table(name = "duel_log", schema = "public")
@Transactional
class DuelLogDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "initiator_discord_id", nullable = false)
    var initiatorDiscordId: Long = 0,

    @Column(name = "opponent_discord_id", nullable = false)
    var opponentDiscordId: Long = 0,

    @Column(name = "winner_discord_id", nullable = false)
    var winnerDiscordId: Long = 0,

    @Column(name = "loser_discord_id", nullable = false)
    var loserDiscordId: Long = 0,

    @Column(name = "stake", nullable = false)
    var stake: Long = 0,

    @Column(name = "pot", nullable = false)
    var pot: Long = 0,

    @Column(name = "loss_tribute", nullable = false)
    var lossTribute: Long = 0,

    @Column(name = "resolved_at", nullable = false)
    var resolvedAt: Instant = Instant.now()
) : Serializable
