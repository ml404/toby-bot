package toby.jpa.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import toby.jpa.dto.ConfigDto;
import toby.jpa.persistence.IConfigPersistence;
import toby.jpa.service.IConfigService;

import java.util.List;

@Service
public class ConfigServiceImpl implements IConfigService {

    @Autowired
    IConfigPersistence configService;

    @Override
    @CacheEvict(value="configs", allEntries=true)
    public List<ConfigDto> listAllConfig() {
        return configService.listAllConfig();
    }

    @Override
    @CacheEvict(value="configs", allEntries=true)
    public List<ConfigDto> listGuildConfig(String guildId) {
        return configService.listGuildConfig(guildId);
    }

    @Override
    @Cacheable(value = "configs", key = "#name+#guildId")
    public ConfigDto getConfigByName(String name, String guildId) {
        return configService.getConfigByName(name, guildId);
    }

    @Override
    public ConfigDto createNewConfig(ConfigDto configDto) {
        return configService.createNewConfig(configDto);
    }

    @Override
    @CacheEvict(value="configs", key="#configDto.name+#configDto.guildId")
    public ConfigDto updateConfig(ConfigDto configDto) {
        return configService.updateConfig(configDto);
    }

    @Override
    public void deleteAll(String guildId){
        configService.deleteAll(guildId);
    }
}
