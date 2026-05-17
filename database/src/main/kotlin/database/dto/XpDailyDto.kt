package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDate

@NamedQueries(
    NamedQuery(
        name = "XpDailyDto.get",
        query = "select d from XpDailyDto d " +
                "where d.discordId = :discordId and d.guildId = :guildId and d.earnDate = :earnDate"
    )
)
@Entity
@Table(name = "xp_daily", schema = "public")
@IdClass(XpDailyId::class)
@Transactional
class XpDailyDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "earn_date")
    var earnDate: LocalDate = LocalDate.now(),

    @Column(name = "xp_earned", nullable = false)
    var xpEarned: Long = 0
) : Serializable

data class XpDailyId(
    var discordId: Long = 0,
    var guildId: Long = 0,
    var earnDate: LocalDate = LocalDate.now()
) : Serializable
