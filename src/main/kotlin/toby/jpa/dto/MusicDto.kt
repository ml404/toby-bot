package toby.jpa.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.*
import org.apache.commons.lang3.EnumUtils
import org.apache.commons.lang3.builder.EqualsBuilder
import org.apache.commons.lang3.builder.HashCodeBuilder
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(name = "MusicDto.getById", query = "select a from MusicDto as a WHERE a.id = :id"),
    NamedQuery(name = "MusicDto.deleteById", query = "delete from MusicDto as a WHERE a.id = :id")
)
@Entity
@Table(name = "music_files", schema = "public")
@Transactional
class MusicDto : Serializable {
    @JvmField
    @Id
    @Column(name = "id")
    @JsonIgnore
    var id: String? = null

    @JvmField
    @Column(name = "file_name")
    var fileName: String? = null

    @Column(name = "file_vol")
    var introVolume: Int = 10

    @JvmField
    @Lob
    @JsonIgnore
    @Column(name = "music_blob", columnDefinition = "TEXT")
    var musicBlob: ByteArray? = null


    constructor()

    constructor(discordId: Long, guildId: Long, fileName: String?, introVolume: Int, musicBlob: ByteArray?) {
        this.id = createMusicId(guildId, discordId)
        this.fileName = fileName
        this.introVolume = introVolume
        this.musicBlob = musicBlob
    }

    enum class Adjustment(val adjustment: String) {
        START("start"),
        END("end");

        companion object {
            fun isValidEnum(enumName: String?): Boolean {
                return EnumUtils.isValidEnum(Adjustment::class.java, enumName)
            }
        }
    }

    private fun createMusicId(guildId: Long, discordId: Long): String {
        return String.format("%s_%s", guildId, discordId)
    }


    override fun toString(): String {
        val sb = StringBuilder("MusicDto{")
        sb.append("id=").append(id)
        sb.append(", fileName=").append(fileName)
        sb.append(", introVolume=").append(introVolume)
        sb.append('}')
        return sb.toString()
    }

    override fun equals(o: Any?): Boolean {
        // If the object is compared with itself then return true
        if (o === this) {
            return true
        }

        /* Check if o is an instance of MusicDto or not
          "null instanceof [type]" also returns false */
        if (o !is MusicDto) {
            return false
        }

        // Compare the data members and return accordingly
        return EqualsBuilder()
            .append(id, o.id)
            .append(fileName, o.fileName)
            .append(introVolume, o.introVolume)
            .append(musicBlob, o.musicBlob)
            .isEquals
    }

    override fun hashCode(): Int {
        return HashCodeBuilder(17, 37)
            .append(id)
            .append(fileName)
            .append(introVolume)
            .append(musicBlob)
            .toHashCode()
    }
}
