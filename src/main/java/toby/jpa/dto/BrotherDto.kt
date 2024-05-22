package toby.jpa.dto

import jakarta.persistence.*
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(name = "BrotherDto.getAll", query = "select a from BrotherDto as a"),
    NamedQuery(name = "BrotherDto.getName", query = "select a from BrotherDto as a WHERE a.brotherName = :name"),
    NamedQuery(name = "BrotherDto.getById", query = "select a from BrotherDto as a WHERE a.discordId = :discordId"),
    NamedQuery(name = "BrotherDto.deleteById", query = "delete from BrotherDto as a WHERE a.discordId = :discordId")
)
@Entity
@Table(name = "brothers", schema = "public")
@Transactional
class BrotherDto : Serializable {
    @JvmField
    @Id
    @Column(name = "discord_id")
    var discordId: Long? = null

    @JvmField
    @Column(name = "brother_name")
    var brotherName: String? = null


    constructor()

    constructor(discordId: Long?, brotherName: String?) {
        this.discordId = discordId
        this.brotherName = brotherName
    }

    override fun toString(): String {
        val sb = StringBuilder("BrotherDto{")
        sb.append("discordId='").append(discordId)
        sb.append(", brotherName=").append(brotherName)
        sb.append('}')
        return sb.toString()
    }
}
