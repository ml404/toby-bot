package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.time.Instant

@NamedQueries(
    NamedQuery(
        name = "VoiceSessionDto.findOpen",
        query = "select s from VoiceSessionDto s where s.discordId = :discordId and s.guildId = :guildId and s.leftAt is null"
    ),
    NamedQuery(
        name = "VoiceSessionDto.findAllOpen",
        query = "select s from VoiceSessionDto s where s.leftAt is null"
    ),
    NamedQuery(
        name = "VoiceSessionDto.sumCountedSecondsInRange",
        query = "select coalesce(sum(s.countedSeconds), 0) from VoiceSessionDto s " +
                "where s.guildId = :guildId and s.discordId = :discordId " +
                "and s.leftAt is not null " +
                "and s.joinedAt >= :from and s.joinedAt < :until"
    ),
    NamedQuery(
        name = "VoiceSessionDto.sumCountedSecondsInRangeAllUsers",
        query = "select s.discordId, coalesce(sum(s.countedSeconds), 0) from VoiceSessionDto s " +
                "where s.guildId = :guildId " +
                "and s.leftAt is not null " +
                "and s.joinedAt >= :from and s.joinedAt < :until " +
                "group by s.discordId"
    ),
    NamedQuery(
        name = "VoiceSessionDto.sumCountedSecondsLifetime",
        query = "select s.discordId, coalesce(sum(s.countedSeconds), 0) from VoiceSessionDto s " +
                "where s.guildId = :guildId and s.leftAt is not null " +
                "group by s.discordId"
    )
)
@Entity
@Table(name = "voice_session", schema = "public")
@Transactional
class VoiceSessionDto(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null,

    @Column(name = "discord_id", nullable = false)
    var discordId: Long = 0,

    @Column(name = "guild_id", nullable = false)
    var guildId: Long = 0,

    @Column(name = "channel_id", nullable = false)
    var channelId: Long = 0,

    @Column(name = "joined_at", nullable = false)
    var joinedAt: Instant = Instant.now(),

    @Column(name = "left_at")
    var leftAt: Instant? = null,

    @Column(name = "counted_seconds")
    var countedSeconds: Long? = null,

    @Column(name = "credits_awarded", nullable = false)
    var creditsAwarded: Long = 0
) : Serializable
