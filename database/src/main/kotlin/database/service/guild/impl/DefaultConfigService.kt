package database.service.guild.impl

import database.dto.guild.ConfigDto
import database.persistence.guild.ConfigPersistence
import database.service.guild.ConfigService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DefaultConfigService : ConfigService {
    @Autowired
    private lateinit var configService: ConfigPersistence

    @Cacheable(value = ["configs"])
    override fun listAllConfig(): List<ConfigDto?> {
        return configService.listAllConfig()
    }

    @Cacheable(value = ["configs"])
    override fun listGuildConfig(guildId: String?): List<ConfigDto?> {
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

    override fun upsertConfig(name: String, value: String, guildId: String): ConfigService.UpsertResult {
        val existing = getConfigByName(name, guildId)
        val dto = ConfigDto(name, value, guildId)
        return if (existing != null && existing.guildId == guildId) {
            val previous = existing.value
            updateConfig(dto)
            ConfigService.UpsertResult.Updated(dto, previousValue = previous)
        } else {
            createNewConfig(dto)
            ConfigService.UpsertResult.Created(dto)
        }
    }

    /**
     * Batched upsert. Class-level transaction-per-method is the default;
     * marking this `@Transactional` puts every row write in one boundary
     * so the underlying [ConfigPersistence] (also `@Transactional`)
     * participates in the outer transaction instead of opening its own
     * per-call. Per-row `@CachePut` annotations on [createNewConfig] /
     * [updateConfig] still fire so the cache stays consistent.
     */
    @Transactional
    override fun upsertAll(
        guildId: String,
        rows: List<Pair<String, String>>,
    ): List<ConfigService.UpsertResult> {
        if (rows.isEmpty()) return emptyList()
        return rows.map { (name, value) -> upsertConfig(name, value, guildId) }
    }

    @CacheEvict(value = ["configs"], allEntries = true)
    override fun deleteAll(guildId: String?) {
        configService.deleteAll(guildId)
    }

    @CacheEvict(value = ["configs"], key = "#name+#guildId")
    override fun deleteConfig(guildId: String?, name: String?) {
        configService.deleteConfig(guildId, name)
    }

    @CacheEvict(value = ["configs"], allEntries = true)
    override fun clearCache() {
    }
}
