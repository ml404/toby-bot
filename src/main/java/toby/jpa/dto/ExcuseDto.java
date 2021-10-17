package toby.jpa.dto;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.*;
import java.io.Serializable;

@NamedQueries({
        @NamedQuery(name = "ExcuseDto.getAll",
                query = "select e from ExcuseDto e WHERE e.guildId = :guildId"),

        @NamedQuery(name = "ExcuseDto.getApproved",
                query = "select e from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = true"),

        @NamedQuery(name = "ExcuseDto.getPending",
                query = "select e from ExcuseDto e WHERE e.guildId = :guildId AND e.approved = false"),

        @NamedQuery(name = "ExcuseDto.getById",
                query = "select e from ExcuseDto e WHERE e.id = :id"),

        @NamedQuery(name = "ExcuseDto.deleteById",
                query = "delete from ExcuseDto e WHERE e.id = :id"),

        @NamedQuery(name = "ExcuseDto.deleteAllByGuildId",
                query = "delete from ExcuseDto e WHERE e.guildId = :guildId")
})

@Entity
@Table(name = "excuse", schema = "public")
@Transactional
public class ExcuseDto implements Serializable {

    @Id
    @Column(name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "guild_id")
    private Long guildId;

    @Column(name = "author")
    private String author;

    @Column(name = "excuse")
    private String excuse;

    @Column(name = "approved")
    private boolean approved = false;


    public ExcuseDto() {
    }


    public ExcuseDto(Integer id, Long guildId, String author, String excuse, boolean approved) {
        this.id = id;
        this.guildId = guildId;
        this.author = author;
        this.excuse = excuse;
        this.approved = approved;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Long getGuildId() {
        return guildId;
    }

    public void setGuildId(Long guildId) {
        this.guildId = guildId;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getExcuse() {
        return excuse;
    }

    public void setExcuse(String excuse) {
        this.excuse = excuse;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }


    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Excuse{");
        sb.append("'id='").append(id);
        sb.append("', guildId='").append(guildId);
        sb.append("', author='").append(author);
        sb.append("', excuse='").append(excuse);
        sb.append("', approved='").append(approved);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        // If the object is compared with itself then return true
        if (o == this) {
            return true;
        }

        /* Check if o is an instance of ExcuseDto or not
          "null instanceof [type]" also returns false */
        if (!(o instanceof ExcuseDto)) {
            return false;
        }

        // typecast o to ExcuseDto so that we can compare data members
        ExcuseDto other = (ExcuseDto) o;

        // Compare the data members and return accordingly
        return new EqualsBuilder()
                .append(id, other.id)
                .append(guildId, other.guildId)
                .append(author, other.author)
                .append(excuse, other.excuse)
                .append(approved, other.approved)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37)
                .append(id)
                .append(guildId)
                .append(author)
                .append(excuse)
                .append(approved)
                .toHashCode();
    }
}
