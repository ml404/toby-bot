package toby.jpa.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
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
    public List<ConfigDto> listConfig() {
        return configService.listConfig();
    }

    @Override
    public ConfigDto getConfigByName(String name, String guildId) {
        return configService.getConfigByName(name, guildId);
    }

    @Override
    public Long createNewConfig(ConfigDto configDto) {
        return configService.createNewConfig(configDto);
    }
}
