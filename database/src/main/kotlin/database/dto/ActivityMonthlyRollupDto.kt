package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDate

@NamedQueries(
    NamedQuery(
        name = "ActivityMonthlyRollupDto.forGuildMonth",
        query = "select r from ActivityMonthlyRollupDto r " +
                "where r.guildId = :guildId and r.monthStart = :monthStart"
    ),
    NamedQuery(
        name = "ActivityMonthlyRollupDto.forUser",
        query = "select r from ActivityMonthlyRollupDto r " +
                "where r.guildId = :guildId and r.discordId = :discordId " +
                "order by r.monthStart desc, r.seconds desc"
    ),
    NamedQuery(
        name = "ActivityMonthlyRollupDto.forUserMonth",
        query = "select r from ActivityMonthlyRollupDto r " +
                "where r.guildId = :guildId and r.discordId = :discordId " +
                "and r.monthStart = :monthStart " +
                "order by r.seconds desc"
    ),
    NamedQuery(
        name = "ActivityMonthlyRollupDto.deleteBefore",
        query = "delete from ActivityMonthlyRollupDto r where r.monthStart < :cutoff"
    )
)
@Suppress("unused") // monthStart is used by NamedQueries above; flagged spuriously by Qodana
@Entity
@Table(name = "activity_monthly_rollup", schema = "public")
@IdClass(ActivityMonthlyRollupId::class)
@Transactional
class ActivityMonthlyRollupDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "month_start")
    var monthStart: LocalDate = LocalDate.now(),

    @Id
    @Column(name = "activity_name")
    var activityName: String = "",

    @Column(name = "seconds", nullable = false)
    var seconds: Long = 0
) : Serializable

data class ActivityMonthlyRollupId(
    var discordId: Long = 0,
    var guildId: Long = 0,
    var monthStart: LocalDate = LocalDate.now(),
    var activityName: String = ""
) : Serializable
