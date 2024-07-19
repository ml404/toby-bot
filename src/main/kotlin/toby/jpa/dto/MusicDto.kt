package toby.jpa.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import org.apache.commons.lang3.EnumUtils
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(name = "MusicDto.getById", query = "select a from MusicDto as a WHERE a.id = :id"),
    NamedQuery(name = "MusicDto.deleteById", query = "delete from MusicDto as a WHERE a.id = :id")
)
@Entity
@Table(name = "music_files", schema = "public")
@Transactional
data class MusicDto(
    @Id
    @Column(name = "id")
    @JsonIgnore
    var id: String? = null,

    @Column(name = "file_name")
    var fileName: String? = null,

    @Column(name = "file_vol")
    var introVolume: Int? = 20,

    @Lob
    @JsonIgnore
    @Column(name = "music_blob", columnDefinition = "TEXT")
    var musicBlob: ByteArray? = null
) : Serializable {

    constructor(
        discordId: Long,
        guildId: Long,
        fileName: String? = null,
        introVolume: Int = 20,
        musicBlob: ByteArray? = null
    ) : this(
        id = createMusicId(guildId, discordId),
        fileName = fileName,
        introVolume = introVolume,
        musicBlob = musicBlob
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

    companion object {
        private fun createMusicId(guildId: Long, discordId: Long): String {
            return "${guildId}_$discordId"
        }
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
        result = 31 * result + (introVolume?.hashCode() ?: 0)
        result = 31 * result + (musicBlob?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "MusicDto(id=$id, fileName=$fileName, introVolume=$introVolume)"
    }
}
