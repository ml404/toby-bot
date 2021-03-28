package toby.jpa.dto;

import javax.persistence.*;
import java.io.Serializable;


@NamedQueries({
        @NamedQuery(name = "ConfigDto.getAll",
                query = "select a from ConfigDto as a"),

        @NamedQuery(name =  "ConfigDto.getValue",
                query = "select a from ConfigDto as a WHERE a.name = :name AND (a.guildId = :guild_id OR a.guildId = 'all')")
})

@Entity
@Table(name="config", schema ="public")
public class ConfigDto implements Serializable {

    @Id
    @Column(name = "name")
    private String name;
    @Column(name = "value")
    private String value;
    @Id
    @Column(name = "guild_id")
    private String guildId;


    public ConfigDto(){
    };

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
        final StringBuilder sb = new StringBuilder("ConfigDto{");
        sb.append("name='").append(name);
        sb.append(", value=").append(value);
        sb.append(", guildId=").append(guildId);
        sb.append('}');
        return sb.toString();
    }

}
