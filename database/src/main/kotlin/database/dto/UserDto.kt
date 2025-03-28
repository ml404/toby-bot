package database.dto

import jakarta.persistence.*
import org.apache.commons.lang3.EnumUtils
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(
        name = "UserDto.getGuildAll",
        query = "select u from UserDto u WHERE u.guildId = :guildId"
    ),
    NamedQuery(
        name = "UserDto.getById",
        query = "select u from UserDto u join u.musicDtos m where u.guildId = :guildId and u.discordId = :discordId"
    ),
    NamedQuery(
        name = "UserDto.deleteById",
        query = "delete from UserDto u WHERE u.guildId = :guildId AND u.discordId = :discordId"
    )
)
@Entity
@Table(name = "\"user\"", schema = "public")
@Transactional
data class UserDto(
    @Id
    @Column(name = "discord_id")
    var discordId: Long = 0,

    @Id
    @Column(name = "guild_id")
    var guildId: Long = 0,

    @Column(name = "super_user")
    var superUser: Boolean = false,

    @Column(name = "music_permission")
    var musicPermission: Boolean = true,

    @Column(name = "dig_permission")
    var digPermission: Boolean = true,

    @Column(name = "meme_permission")
    var memePermission: Boolean = true,

    @Column(name = "social_credit")
    var socialCredit: Long? = 0L,

    @Column(name = "initiative")
    var initiativeModifier: Int? = 0,

    @OneToMany(mappedBy = "userDto", fetch = FetchType.EAGER, cascade = [CascadeType.ALL], orphanRemoval = true)
    var musicDtos: MutableList<MusicDto> = mutableListOf()
) : Serializable {

    enum class Permissions(val permission: String) {
        MUSIC("music"),
        MEME("meme"),
        DIG("dig"),
        SUPERUSER("superuser");

        companion object {
            fun isValidEnum(enumName: String?): Boolean {
                return EnumUtils.isValidEnum(Permissions::class.java, enumName)
            }
        }
    }

    override fun toString(): String {
        return "UserDto(discordId=$discordId, guildId=$guildId)"
    }

    fun getPermissionsAsString(): String {
        val permissionsMap = mapOf(
            "MUSIC" to musicPermission,
            "MEME" to memePermission,
            "DIG" to digPermission,
            "SUPERUSER" to superUser
        )
        return permissionsMap.entries.joinToString(separator = ", ") { "${it.key}: ${it.value}" }
    }
}
