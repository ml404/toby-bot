package toby.jpa.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
public class MusicDto implements Serializable {


    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "file_name")
    private String fileName;

    @Lob
    @JsonIgnore
    @Column(name = "music_blob", columnDefinition="TEXT")
    private String musicBlob;


    public MusicDto() {
    }

    public MusicDto(Long discordId, Long guildId, String fileName, String musicBlob) {
        this.id = createMusicId(guildId, discordId);
        this.fileName = fileName;
        this.musicBlob = musicBlob;

    }

    private String createMusicId(Long guildId, Long discordId) {
        return String.format("%s_%s", guildId, discordId);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("MusicDto{");
        sb.append("id=").append(id);
        sb.append(", fileName=").append(fileName);
        sb.append('}');
        return sb.toString();
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

    public String getMusicBlob() {
        return musicBlob;
    }

    public void setMusicBlob(String musicBlob) {
        this.musicBlob = musicBlob;
    }


}
