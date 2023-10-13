package toby.jpa.dto;

import jakarta.persistence.*;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;

@NamedQueries({
        @NamedQuery(name = "BrotherDto.getAll",
                query = "select a from BrotherDto as a"),

        @NamedQuery(name =  "BrotherDto.getName",
                query = "select a from BrotherDto as a WHERE a.brotherName = :name"),

        @NamedQuery(name =  "BrotherDto.getById",
                query = "select a from BrotherDto as a WHERE a.discordId = :discordId"),

        @NamedQuery(name =  "BrotherDto.deleteById",
                query = "delete from BrotherDto as a WHERE a.discordId = :discordId")
})

@Entity
@Table(name="brothers", schema ="public")
@Transactional
public class BrotherDto implements Serializable {

    @Id
    @Column(name = "discord_id")
    private Long discordId;
    @Column(name = "brother_name")
    private String brotherName;


    public BrotherDto(){
    };

    public BrotherDto(Long discordId, String brotherName) {
        this.discordId = discordId;
        this.brotherName = brotherName;
    }

    public Long getDiscordId() {
        return discordId;
    }

    public void setDiscordId(Long discordId) {
        this.discordId = discordId;
    }

    public String getBrotherName() {
        return brotherName;
    }

    public void setBrotherName(String brotherName) {
        this.brotherName = brotherName;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("BrotherDto{");
        sb.append("discordId='").append(discordId);
        sb.append(", brotherName=").append(brotherName);
        sb.append('}');
        return sb.toString();
    }
}
