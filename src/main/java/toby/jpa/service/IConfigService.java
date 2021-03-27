package toby.jpa.service;

import toby.jpa.dto.ConfigDto;

import java.util.List;

public interface IConfigService {

    public List<ConfigDto> listConfig();

    public ConfigDto getConfigByName(String name, String guildId);

    public Long createNewConfig(ConfigDto configDto);

}
