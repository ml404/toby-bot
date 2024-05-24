package toby.jpa.dto

import jakarta.persistence.*
import org.apache.commons.lang3.builder.EqualsBuilder
import org.apache.commons.lang3.builder.HashCodeBuilder
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable

@NamedQueries(
    NamedQuery(name = "ExcuseDto.getAll", query = "select e from ExcuseDto e WHERE e.guildId = :guildId"),

    NamedQuery(name = "ExcuseDto.getApproved", query = "select e from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = true"),

    NamedQuery(name = "ExcuseDto.getPending", query = "select e from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = false"),

    NamedQuery(name = "ExcuseDto.getById", query = "select e from ExcuseDto e WHERE e.id = :id"),

    NamedQuery(name = "ExcuseDto.deleteById", query = "delete from ExcuseDto e WHERE e.id = :id"),

    NamedQuery(name = "ExcuseDto.deleteAllByGuildId", query = "delete from ExcuseDto e WHERE e.guildId = :guildId")
)
@Entity
@Table(name = "excuse", schema = "public")
@Transactional
class ExcuseDto : Serializable {
    @JvmField
    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @JvmField
    @Column(name = "guild_id")
    var guildId: Long? = null

    @JvmField
    @Column(name = "author")
    var author: String? = null

    @JvmField
    @Column(name = "excuse")
    var excuse: String? = null

    @Column(name = "approved")
    var approved: Boolean = false


    constructor()


    constructor(id: Long?, guildId: Long?, author: String?, excuse: String?, approved: Boolean) {
        this.id = id
        this.guildId = guildId
        this.author = author
        this.excuse = excuse
        this.approved = approved
    }


    override fun toString(): String {
        val sb = StringBuilder("Excuse{")
        sb.append("'id='").append(id)
        sb.append("', guildId='").append(guildId)
        sb.append("', author='").append(author)
        sb.append("', excuse='").append(excuse)
        sb.append("', approved='").append(approved)
        sb.append('}')
        return sb.toString()
    }

    override fun equals(o: Any?): Boolean {
        // If the object is compared with itself then return true
        if (o === this) {
            return true
        }

        /* Check if o is an instance of ExcuseDto or not
          "null instanceof [type]" also returns false */
        if (o !is ExcuseDto) {
            return false
        }

        // Compare the data members and return accordingly
        return EqualsBuilder()
            .append(id, o.id)
            .append(guildId, o.guildId)
            .append(author, o.author)
            .append(excuse, o.excuse)
            .append(approved, o.approved)
            .isEquals
    }

    override fun hashCode(): Int {
        return HashCodeBuilder(17, 37)
            .append(id)
            .append(guildId)
            .append(author)
            .append(excuse)
            .append(approved)
            .toHashCode()
    }
}
