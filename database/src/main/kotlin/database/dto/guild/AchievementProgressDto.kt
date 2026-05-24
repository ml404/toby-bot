package database.dto.guild

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "AchievementProgressDto.get",
        query = "select p from AchievementProgressDto p " +
                "where p.discordId = :discordId and p.guildId = :guildId and p.achievementId = :achievementId"
    ),
    NamedQuery(
        name = "AchievementProgressDto.getByUser",
        query = "select p from AchievementProgressDto p " +
                "where p.discordId = :discordId and p.guildId = :guildId"
    )
)
@Entity
@Table(name = "achievement_progress", schema = "public")
@IdClass(AchievementProgressId::class)
@Transactional
class AchievementProgressDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Id
    @Column(name = "achievement_id")
    var achievementId: Long = 0,

    @Column(name = "progress", nullable = false)
    var progress: Long = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) : Serializable

data class AchievementProgressId(
    var discordId: Long = 0,
    var guildId: Long = 0,
    var achievementId: Long = 0
) : Serializable
