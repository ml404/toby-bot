package toby.jpa.persistence;

import toby.jpa.dto.ConfigDto;

import java.util.List;

public interface IConfigPersistence {
    public List<ConfigDto> listConfig();

    public ConfigDto getConfigByName(String name);

    public Long createNewConfig(ConfigDto configDto);

}
