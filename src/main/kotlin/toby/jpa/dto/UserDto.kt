package toby.jpa.dto

import jakarta.persistence.*
import org.apache.commons.lang3.EnumUtils
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(
        name = "UserDto.getGuildAll",
        query = "select u from UserDto u join MusicDto m on u.musicDto.id = m.id WHERE u.guildId = :guildId"
    ),
    NamedQuery(
        name = "UserDto.getById",
        query = "select u from UserDto u join MusicDto m on u.musicDto.id = m.id WHERE u.guildId = :guildId AND u.discordId = :discordId"
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

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "music_file_id", referencedColumnName = "id")
    var musicDto: MusicDto? = null
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
        return "UserDto(discordId=$discordId, guildId=$guildId, socialCredit=$socialCredit, initiativeModifier=$initiativeModifier, isSuperUser=$superUser, musicPermission=$musicPermission, digPermission=$digPermission, memePermission=$memePermission, musicDto=$musicDto)"
    }
}
