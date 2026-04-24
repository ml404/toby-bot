package database.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(name = "ConfigDto.getAll", query = "select a from ConfigDto as a"),
    NamedQuery(name = "ConfigDto.getGuildAll", query = "select a from ConfigDto as a WHERE a.guildId = :guildId "),
    NamedQuery(
        name = "ConfigDto.getValue",
        query = "select a from ConfigDto as a WHERE a.name = :name AND (a.guildId = :guildId OR a.guildId = 'all')"
    )
)
@Entity
@Table(name = "config", schema = "public")
@Transactional
class ConfigDto(
    @Id
    @Column(name = "name")
    var name: String? = null,

    @Column(name = "\"value\"")
    var value: String? = null,

    @Id
    @Column(name = "guild_id")
    var guildId: String? = null
) : Serializable {

    enum class Configurations(val configValue: String) {
        INTRO_VOLUME("DEFAULT_INTRO_VOLUME"),
        VOLUME("DEFAULT_VOLUME"),
        MOVE("DEFAULT_MOVE_CHANNEL"),
        DELETE_DELAY("DELETE_MESSAGE_DELAY"),
        LEADERBOARD_CHANNEL("LEADERBOARD_CHANNEL"),
        ACTIVITY_TRACKING("ACTIVITY_TRACKING"),
        ACTIVITY_TRACKING_NOTIFIED("ACTIVITY_TRACKING_NOTIFIED");
    }

    override fun toString(): String {
        return "ConfigDto{name='$name', value=$value, guildId=$guildId}"
    }
}
