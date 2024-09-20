package toby.jpa.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import org.apache.commons.lang3.EnumUtils
import org.springframework.transaction.annotation.Transactional
import toby.helpers.FileUtils.computeHash
import java.io.Serializable

@NamedQueries(
    NamedQuery(name = "MusicDto.getById", query = "select m from MusicDto as m WHERE m.id = :id"),
    NamedQuery(name = "MusicDto.deleteById", query = "delete from MusicDto as m WHERE m.id = :id"),
    NamedQuery(
        name = "MusicDto.deleteByUser",
        query = "delete from MusicDto as m WHERE m.userDto.discordId = :discordId AND m.userDto.guildId = :guildId"
    )
)
@Entity
@Table(name = "music_files", schema = "public")
@Transactional
data class MusicDto(
    @Id
    @Column(name = "id")
    @JsonIgnore
    var id: String? = null,

    @ManyToOne
    @JoinColumns(
        JoinColumn(name = "discord_id", referencedColumnName = "discord_id"),
        JoinColumn(name = "guild_id", referencedColumnName = "guild_id")
    )
    var userDto: UserDto? = null,

    @Column(name = "file_name")
    var fileName: String? = null,

    @Column(name = "file_vol")
    var introVolume: Int? = 20,

    @Column(name = "index")
    var index: Int? = 1,

    @Lob
    @JsonIgnore
    @Column(name = "music_blob", columnDefinition = "BYTEA")
    var musicBlob: ByteArray? = null,

    @Column(name = "music_blob_hash")
    var musicBlobHash: String? = null
) : Serializable {

    constructor(
        userDto: UserDto,
        index: Int = 1,
        fileName: String? = null,
        introVolume: Int = 20,
        musicBlob: ByteArray? = null
    ) : this(
        id = "${userDto.guildId}_${userDto.discordId}_${index}",
        index = index,
        userDto = userDto,
        fileName = fileName,
        introVolume = introVolume,
        musicBlob = musicBlob,
        musicBlobHash = musicBlob?.let { computeHash(it) }
    )

    enum class Adjustment(val adjustment: String) {
        START("start"),
        END("end");

        companion object {
            fun isValidEnum(enumName: String?): Boolean {
                return EnumUtils.isValidEnum(Adjustment::class.java, enumName)
            }
        }
    }

    override fun toString(): String {
        val blobPreview = musicBlob?.take(10)?.joinToString(", ") { it.toString() } // First 10 bytes
        return "MusicDto(id=$id, fileName=$fileName, introVolume=$introVolume, musicBlobPreview=[$blobPreview])"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MusicDto

        if (id != other.id) return false
        if (fileName != other.fileName) return false
        if (introVolume != other.introVolume) return false
        if (musicBlob != null) {
            if (other.musicBlob == null) return false
            if (!musicBlob.contentEquals(other.musicBlob)) return false
        } else if (other.musicBlob != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + (fileName?.hashCode() ?: 0)
        result = 31 * result + (introVolume ?: 0)
        result = 31 * result + (musicBlob?.contentHashCode() ?: 0)
        return result
    }
}