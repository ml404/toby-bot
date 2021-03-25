package toby.jpa.dto;

import javax.persistence.*;
import java.io.Serializable;


@NamedQueries({
        @NamedQuery(name = "ConfigDto.getAll",
                query = "select a from ConfigDto as a"),

        @NamedQuery(name =  "ConfigDto.getValue",
                query = "select a from ConfigDto as a WHERE a.name = :name")
})

@Entity
@Table(name="config", schema ="public")
public class ConfigDto implements Serializable {

    @Id
    @Column(name = "name")
    private String name;
    @Column(name = "value")
    private String value;


    public ConfigDto(){
    };

    public ConfigDto(String name, String value) {
        this.name = name;
        this.value = value;
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

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ConfigDto{");
        sb.append("name='").append(name);
        sb.append(", value=").append(value);
        sb.append('}');
        return sb.toString();
    }
}
