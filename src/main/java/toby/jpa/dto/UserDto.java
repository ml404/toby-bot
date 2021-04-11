package toby.jpa.dto;

import org.apache.commons.lang3.EnumUtils;

import javax.persistence.*;
import java.io.Serializable;

@NamedQueries({
        @NamedQuery(name = "UserDto.getGuildAll",
                query = "select a from UserDto as a WHERE a.guildId = :guildId"),

        @NamedQuery(name = "UserDto.getById",
                query = "select a from UserDto as a WHERE a.discordId = :discordId AND a.guildId = :guildId"),

        @NamedQuery(name = "UserDto.deleteById",
                query = "delete from UserDto as a WHERE a.discordId = :discordId AND a.guildId = :guildId")
})

@Entity
@Table(name = "user", schema = "public")
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


    public enum Permissions {
        MUSIC("music"),
        MEME("meme"),
        DIG("dig"),
        SUPERUSER("superuser");

        private final String permission;

        Permissions(String permision) {
            this.permission = permision;
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

    public UserDto(Long discordId, Long guildId, boolean superUser, boolean musicPermission, boolean digPermission, boolean memePermission) {
        this.discordId = discordId;
        this.guildId = guildId;
        this.superUser = superUser;
        this.musicPermission = musicPermission;
        this.digPermission = digPermission;
        this.memePermission = memePermission;
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

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("UserDto{");
        sb.append("discordId='").append(discordId);
        sb.append(", guildId=").append(guildId);
        sb.append(", superUser=").append(superUser);
        sb.append(", musicPermission=").append(musicPermission);
        sb.append(", digPermission=").append(digPermission);
        sb.append(", memePermission=").append(memePermission);
        sb.append('}');
        return sb.toString();
    }

}
