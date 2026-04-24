package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDate

@NamedQueries(
    NamedQuery(
        name = "VoiceCreditDailyDto.get",
        query = "select d from VoiceCreditDailyDto d " +
                "where d.discordId = :discordId and d.guildId = :guildId and d.earnDate = :earnDate"
    )
)
@Entity
@Table(name = "voice_credit_daily", schema = "public")
@IdClass(VoiceCreditDailyId::class)
@Transactional
class VoiceCreditDailyDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "earn_date")
    var earnDate: LocalDate = LocalDate.now(),

    @Column(name = "credits", nullable = false)
    var credits: Long = 0
) : Serializable

data class VoiceCreditDailyId(
    var discordId: Long = 0,
    var guildId: Long = 0,
    var earnDate: LocalDate = LocalDate.now()
) : Serializable
