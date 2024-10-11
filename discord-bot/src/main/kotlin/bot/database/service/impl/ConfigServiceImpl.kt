package bot.database.service.impl

import bot.database.dto.ConfigDto
import bot.database.persistence.IConfigPersistence
import bot.database.service.IConfigService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

@Service
open class ConfigServiceImpl : IConfigService {
    @Autowired
    lateinit var configService: IConfigPersistence

    @Cacheable(value = ["configs"])
    override fun listAllConfig(): List<ConfigDto?>? {
        return configService.listAllConfig()
    }

    @Cacheable(value = ["configs"])
    override fun listGuildConfig(guildId: String?): List<ConfigDto?>? {
        return configService.listGuildConfig(guildId)
    }

    @CachePut(value = ["configs"], key = "#name+#guildId")
    override fun getConfigByName(name: String?, guildId: String?): ConfigDto? {
        return configService.getConfigByName(name, guildId)
    }

    @CachePut(value = ["configs"], key = "#configDto.name+#configDto.guildId")
    override fun createNewConfig(configDto: ConfigDto): ConfigDto {
        return configService.createNewConfig(configDto)
    }

    @CachePut(value = ["configs"], key = "#configDto.name+#configDto.guildId")
    override fun updateConfig(configDto: ConfigDto?): ConfigDto? {
        return configService.updateConfig(configDto)
    }

    @CacheEvict(value = ["configs"], allEntries = true)
    override fun deleteAll(guildId: String?) {
        configService.deleteAll(guildId)
    }

    @CacheEvict(value = ["configs"], key = "#configDto.name+#configDto.guildId")
    override fun deleteConfig(guildId: String?, name: String?) {
        configService.deleteConfig(guildId, name)
    }

    @CacheEvict(value = ["configs"], allEntries = true)
    override fun clearCache() {
    }
}
