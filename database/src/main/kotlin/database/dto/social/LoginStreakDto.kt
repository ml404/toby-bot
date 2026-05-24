package database.dto.social

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.LocalDate

@NamedQueries(
    NamedQuery(
        name = "LoginStreakDto.get",
        query = "select s from LoginStreakDto s " +
                "where s.discordId = :discordId and s.guildId = :guildId"
    ),
    NamedQuery(
        name = "LoginStreakDto.findActiveStreaksDueForReminder",
        query = "select s from LoginStreakDto s " +
                "where s.guildId = :guildId " +
                "and s.currentStreak > 0 " +
                "and (s.lastClaimDate is null or s.lastClaimDate < :today)"
    )
)
@Entity
@Table(name = "login_streak", schema = "public")
@IdClass(LoginStreakId::class)
@Transactional
class LoginStreakDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Column(name = "current_streak", nullable = false)
    var currentStreak: Int = 0,

    @Column(name = "longest_streak", nullable = false)
    var longestStreak: Int = 0,

    @Column(name = "last_claim_date")
    var lastClaimDate: LocalDate? = null,

    @Column(name = "total_claims", nullable = false)
    var totalClaims: Long = 0
) : Serializable

data class LoginStreakId(
    var discordId: Long = 0,
    var guildId: Long = 0
) : Serializable
