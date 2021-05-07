package toby.jpa.dto;

import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.*;
import java.io.Serializable;

@NamedQueries({
        @NamedQuery(name = "UserDto.getGuildAll",
                query = "select u from UserDto u join MusicDto m on u.musicDto.id = m.id WHERE u.guildId = :guildId"),

        @NamedQuery(name = "UserDto.getById",
                query = "select u from UserDto u join MusicDto m on u.musicDto.id = m.id WHERE u.guildId = :guildId AND u.discordId = :discordId"),

        @NamedQuery(name = "UserDto.deleteById",
                query = "delete from UserDto u WHERE u.guildId = :guildId AND u.discordId = :discordId")
})

@Entity
@Table(name = "user", schema = "public")
@Transactional
public class UserDto implements Serializable {

    @Id
    @Column(name = "discord_id")
    private Long discordId;
    @Id
    @Column(name = "guild_id")
    private Long guildId;

    @Column(name = "super_user")
    private boolean superUser;

    @Column(name = "music_permission")
    private boolean musicPermission = true;

    @Column(name = "dig_permission")
    private boolean digPermission = true;

    @Column(name = "meme_permission")
    private boolean memePermission = true;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "music_file_id", referencedColumnName = "id")
    private MusicDto musicDto;

    public enum Permissions {
        MUSIC("music"),
        MEME("meme"),
        DIG("dig"),
        SUPERUSER("superuser");

        private final String permission;

        Permissions(String permission) {
            this.permission = permission;
        }

        public String getPermission() {
            return this.permission;
        }

        public static Boolean isValidEnum(String enumName) {
            return EnumUtils.isValidEnum(Permissions.class, enumName);
        }
    }

    public UserDto() {
    }

    ;

    public UserDto(Long discordId, Long guildId, boolean superUser, boolean musicPermission, boolean digPermission, boolean memePermission, MusicDto musicDto) {
        this.discordId = discordId;
        this.guildId = guildId;
        this.superUser = superUser;
        this.musicPermission = musicPermission;
        this.digPermission = digPermission;
        this.memePermission = memePermission;
        this.musicDto = musicDto;
    }


    public Long getDiscordId() {
        return discordId;
    }

    public void setDiscordId(Long discordId) {
        this.discordId = discordId;
    }

    public Long getGuildId() {
        return guildId;
    }

    public void setGuildId(Long guildId) {
        this.guildId = guildId;
    }


    public boolean isSuperUser() {
        return superUser;
    }

    public void setSuperUser(boolean superUser) {
        this.superUser = superUser;
    }

    public boolean hasMusicPermission() {
        return musicPermission;
    }

    public void setMusicPermission(boolean musicPermission) {
        this.musicPermission = musicPermission;
    }

    public boolean hasDigPermission() {
        return digPermission;
    }

    public void setDigPermission(boolean digPermission) {
        this.digPermission = digPermission;
    }

    public boolean hasMemePermission() {
        return memePermission;
    }

    public void setMemePermission(boolean memePermission) {
        this.memePermission = memePermission;
    }



    public MusicDto getMusicDto() {
        return musicDto;
    }

    public void setMusicDto(MusicDto musicDto) {
        this.musicDto = musicDto;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("User{");
        sb.append("discordId='").append(discordId);
        sb.append("', guildId='").append(guildId);
        sb.append("', superUser=").append(superUser);
        sb.append(", musicPermission=").append(musicPermission);
        sb.append(", digPermission=").append(digPermission);
        sb.append(", memePermission=").append(memePermission);
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
        if (!(o instanceof UserDto)) {
            return false;
        }

        // typecast o to MusicDto so that we can compare data members
        UserDto other = (UserDto) o;

        // Compare the data members and return accordingly
        return new EqualsBuilder()
                .append(discordId, other.discordId)
                .append(guildId, other.guildId)
                .append(superUser, other.superUser)
                .append(musicPermission, other.musicPermission)
                .append(digPermission, other.digPermission)
                .append(memePermission, other.memePermission)
                .append(musicDto, other.musicDto)
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37)
                .append(discordId)
                .append(guildId)
                .append(superUser)
                .append(musicPermission)
                .append(digPermission)
                .append(memePermission)
                .append(musicDto)
                .toHashCode();
    }

}
