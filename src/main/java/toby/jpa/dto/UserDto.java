package toby.jpa.dto;

import jakarta.persistence.*;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.springframework.transaction.annotation.Transactional;

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
@Table(name = "\"user\"", schema = "public")
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

    @Column(name = "social_credit")
    private Long socialCredit = 0L;

    @Column(name="initiative")
    private int initiative = 0;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "music_file_id", referencedColumnName = "id")
    private MusicDto musicDto;

    public int getInitiative() {
        return 0;
    }

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

    public UserDto(Long discordId, Long guildId, boolean superUser, boolean musicPermission, boolean digPermission, boolean memePermission, Long socialCredit, MusicDto musicDto) {
        this.discordId = discordId;
        this.guildId = guildId;
        this.socialCredit = socialCredit;
        this.superUser = superUser;
        this.musicPermission = musicPermission;
        this.digPermission = digPermission;
        this.memePermission = memePermission;
        this.musicDto = musicDto;
    }

    public UserDto(Long discordId, Long guildId, boolean superUser, boolean musicPermission, boolean digPermission, boolean memePermission, Long socialCredit, int initiative, MusicDto musicDto) {
        this.discordId = discordId;
        this.guildId = guildId;
        this.socialCredit = socialCredit;
        this.superUser = superUser;
        this.musicPermission = musicPermission;
        this.digPermission = digPermission;
        this.memePermission = memePermission;
        this.musicDto = musicDto;
        this.initiative = initiative;
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

    public Long getSocialCredit() {
        return socialCredit;
    }

    public void setSocialCredit(Long socialCredit) {
        this.socialCredit = socialCredit;
    }

    public void setInitiativeModifier(Integer modifier) {
        initiative = modifier;
    }

    public int getInitiativeModifier() {
        return initiative;
    }

    public MusicDto getMusicDto() {
        return musicDto;
    }

    public void setMusicDto(MusicDto musicDto) {
        this.musicDto = musicDto;
    }

    @Override
    public String toString() {
        return "User{" + "discordId='" + discordId +
                "', guildId='" + guildId +
                "', socialCredit='" + socialCredit +
                "', initiative='" + initiative +
                "', superUser='" + superUser +
                "', musicPermission='" + musicPermission +
                "', digPermission='" + digPermission +
                "', memePermission='" + memePermission +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        // If the object is compared with itself then return true
        if (o == this) {
            return true;
        }

        /* Check if o is an instance of UserDto or not
          "null instanceof [type]" also returns false */
        if (!(o instanceof UserDto other)) {
            return false;
        }

        // Compare the data members and return accordingly
        return new EqualsBuilder()
                .append(discordId, other.discordId)
                .append(guildId, other.guildId)
                .append(socialCredit, other.socialCredit)
                .append(initiative, other.initiative)
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
                .append(socialCredit)
                .append(initiative)
                .append(superUser)
                .append(musicPermission)
                .append(digPermission)
                .append(memePermission)
                .append(musicDto)
                .toHashCode();
    }

}
