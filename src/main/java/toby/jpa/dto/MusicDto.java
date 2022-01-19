package toby.jpa.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.*;
import java.io.Serializable;

@NamedQueries({

        @NamedQuery(name = "MusicDto.getById",
                query = "select a from MusicDto as a WHERE a.id = :id"),

        @NamedQuery(name = "MusicDto.deleteById",
                query = "delete from MusicDto as a WHERE a.id = :id")
})

@Entity
@Table(name = "music_files", schema = "public")
@Transactional
public class MusicDto implements Serializable {


    @Id
    @Column(name = "id")
    @JsonIgnore
    private String id;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "file_vol")
    private int introVolume;

    @Lob
    @JsonIgnore
    @Column(name = "music_blob", columnDefinition = "TEXT")
    private byte[] musicBlob;


    public MusicDto() {
    }

    public MusicDto(Long discordId, Long guildId, String fileName, int introVolume, byte[] musicBlob) {
        this.id = createMusicId(guildId, discordId);
        this.fileName = fileName;
        this.introVolume = introVolume;
        this.musicBlob = musicBlob;

    }

    private String createMusicId(Long guildId, Long discordId) {
        return String.format("%s_%s", guildId, discordId);
    }


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public int getIntroVolume() {
        return introVolume;
    }

    public void setIntroVolume(int introVolume) {
        this.introVolume = introVolume;
    }

    public byte[] getMusicBlob() {
        return musicBlob;
    }

    public void setMusicBlob(byte[] musicBlob) {
        this.musicBlob = musicBlob;
    }


    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("MusicDto{");
        sb.append("id=").append(id);
        sb.append(", fileName=").append(fileName);
        sb.append(", introVolume=").append(introVolume);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        // If the object is compared with itself then return true
        if (o == this) {
            return true;
        }

        /* Check if o is an instance of MusicDto or not
          "null instanceof [type]" also returns false */
        if (!(o instanceof MusicDto)) {
            return false;
        }

        // typecast o to MusicDto so that we can compare data members
        MusicDto other = (MusicDto) o;

        // Compare the data members and return accordingly
        return new EqualsBuilder()
                .append(id, other.id)
                .append(fileName, other.fileName)
                .append(introVolume, other.introVolume)
                .append(musicBlob, other.musicBlob)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37)
                .append(id)
                .append(fileName)
                .append(introVolume)
                .append(musicBlob)
                .toHashCode();
    }
}
