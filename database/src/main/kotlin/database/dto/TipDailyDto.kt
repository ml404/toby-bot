package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDate

@NamedQueries(
    NamedQuery(
        name = "TipDailyDto.get",
        query = "select d from TipDailyDto d " +
                "where d.senderDiscordId = :senderDiscordId and d.guildId = :guildId and d.tipDate = :tipDate"
    )
)
@Entity
@Table(name = "tip_daily", schema = "public")
@IdClass(TipDailyId::class)
@Transactional
class TipDailyDto(
    @Id
    @Column(name = "sender_discord_id")
    var senderDiscordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "tip_date")
    var tipDate: LocalDate = LocalDate.now(),

    @Column(name = "credits_sent", nullable = false)
    var creditsSent: Long = 0
) : Serializable

data class TipDailyId(
    var senderDiscordId: Long = 0,
    var guildId: Long = 0,
    var tipDate: LocalDate = LocalDate.now()
) : Serializable
