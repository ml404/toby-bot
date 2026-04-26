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
@Table(name = "tip_log", schema = "public")
@Transactional
class TipLogDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "sender_discord_id", nullable = false)
    var senderDiscordId: Long = 0,

    @Column(name = "recipient_discord_id", nullable = false)
    var recipientDiscordId: Long = 0,

    @Column(name = "amount", nullable = false)
    var amount: Long = 0,

    @Column(name = "note")
    var note: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
) : Serializable
