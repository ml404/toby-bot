package toby.jpa.dto;

import jakarta.persistence.*;
import org.apache.commons.lang3.EnumUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;


@NamedQueries({
        @NamedQuery(name = "ConfigDto.getAll",
                query = "select a from ConfigDto as a"),

        @NamedQuery(name = "ConfigDto.getGuildAll",
                query = "select a from ConfigDto as a WHERE a.guildId = :guildId "),

        @NamedQuery(name =  "ConfigDto.getValue",
                query = "select a from ConfigDto as a WHERE a.name = :name AND (a.guildId = :guildId OR a.guildId = 'all')")
})

@Entity
@Table(name="config", schema ="public")
@Transactional
public class ConfigDto implements Serializable {

    @Id
    @Column(name = "name")
    private String name;
    @Column(name = "\"value\"")
    private String value;
    @Id
    @Column(name = "guild_id")
    private String guildId;


    public enum Configurations {
        VOLUME("DEFAULT_VOLUME"),
        MOVE("DEFAULT_MOVE_CHANNEL"),
        DELETE_DELAY("DELETE_MESSAGE_DELAY");

        private final String configValue;

        Configurations(String configName) {
            this.configValue = configName;
        }

        public String getConfigValue() {
            return this.configValue;
        }

        public static Boolean isValidEnum(String enumName) {
            return EnumUtils.isValidEnum(ConfigDto.Configurations.class, enumName);
        }
    }

    public ConfigDto(){
    }

    public ConfigDto(String name, String value, String guildId) {
        this.name = name;
        this.value = value;
        this.guildId = guildId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }


    public String getGuildId() {
        return guildId;
    }

    public void setGuildId(String guildId) {
        this.guildId = guildId;
    }

    @Override
    public String toString() {
        return "ConfigDto{" + "name='" + name +
                ", value=" + value +
                ", guildId=" + guildId +
                '}';
    }

}
