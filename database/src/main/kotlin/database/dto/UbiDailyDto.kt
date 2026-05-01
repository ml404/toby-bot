package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDate

@NamedQueries(
    NamedQuery(
        name = "UbiDailyDto.get",
        query = "select d from UbiDailyDto d " +
                "where d.discordId = :discordId and d.guildId = :guildId and d.grantDate = :grantDate"
    )
)
@Entity
@Table(name = "ubi_daily", schema = "public")
@IdClass(UbiDailyId::class)
@Transactional
class UbiDailyDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "grant_date")
    var grantDate: LocalDate = LocalDate.now(),

    @Column(name = "credits_granted", nullable = false)
    var creditsGranted: Long = 0
) : Serializable

data class UbiDailyId(
    var discordId: Long = 0,
    var guildId: Long = 0,
    var grantDate: LocalDate = LocalDate.now()
) : Serializable
