package toby.jpa.persistence;

import toby.jpa.dto.ConfigDto;

import java.util.List;

public interface IConfigPersistence {
    List<ConfigDto> listAllConfig();
    List<ConfigDto> listGuildConfig(String guildId);
    ConfigDto getConfigByName(String name, String guildId);
    ConfigDto createNewConfig(ConfigDto configDto);
    ConfigDto updateConfig(ConfigDto configDto);
    void deleteAll(String guildId);
    void deleteConfig(String guildId, String name);
}
