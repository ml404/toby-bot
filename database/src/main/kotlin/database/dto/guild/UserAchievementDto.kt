package database.dto.guild

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "UserAchievementDto.getByUser",
        query = "select u from UserAchievementDto u " +
                "where u.discordId = :discordId and u.guildId = :guildId " +
                "order by u.unlockedAt desc"
    ),
    NamedQuery(
        name = "UserAchievementDto.exists",
        query = "select count(u) from UserAchievementDto u " +
                "where u.discordId = :discordId and u.guildId = :guildId and u.achievementId = :achievementId"
    )
)
@Entity
@Table(name = "user_achievement", schema = "public")
@IdClass(UserAchievementId::class)
@Transactional
class UserAchievementDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "achievement_id")
    var achievementId: Long = 0,

    @Column(name = "unlocked_at", nullable = false)
    var unlockedAt: Instant = Instant.now()
) : Serializable

data class UserAchievementId(
    var discordId: Long = 0,
    var guildId: Long = 0,
    var achievementId: Long = 0
) : Serializable
