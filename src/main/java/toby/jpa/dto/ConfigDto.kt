package toby.jpa.dto

import jakarta.persistence.*
import org.apache.commons.lang3.EnumUtils
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
class ConfigDto : Serializable {
    @JvmField
    @Id
    @Column(name = "name")
    var name: String? = null

    @Column(name = "\"value\"")
    var value: String? = null

    @JvmField
    @Id
    @Column(name = "guild_id")
    var guildId: String? = null


    enum class Configurations(@JvmField val configValue: String) {
        VOLUME("DEFAULT_VOLUME"),
        MOVE("DEFAULT_MOVE_CHANNEL"),
        DELETE_DELAY("DELETE_MESSAGE_DELAY");

        companion object {
            fun isValidEnum(enumName: String?): Boolean {
                return EnumUtils.isValidEnum(Configurations::class.java, enumName)
            }
        }
    }

    constructor()

    constructor(name: String?, value: String?, guildId: String?) {
        this.name = name
        this.value = value
        this.guildId = guildId
    }


    override fun toString(): String {
        return "ConfigDto{" + "name='" + name +
                ", value=" + value +
                ", guildId=" + guildId +
                '}'
    }
}
