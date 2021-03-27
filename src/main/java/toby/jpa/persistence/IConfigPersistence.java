package toby.jpa.persistence;

import toby.jpa.dto.ConfigDto;

import java.util.List;

public interface IConfigPersistence {
    public List<ConfigDto> listConfig();

    public ConfigDto getConfigByName(String name, String guildId);

    public Long createNewConfig(ConfigDto configDto);

}
