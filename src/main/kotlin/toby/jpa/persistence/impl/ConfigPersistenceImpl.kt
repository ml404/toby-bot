package toby.jpa.persistence.impl

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import toby.jpa.dto.ConfigDto
import toby.jpa.persistence.IConfigPersistence

@Repository
@Transactional
open class ConfigPersistenceImpl : IConfigPersistence {

    @PersistenceContext
    lateinit var entityManager: EntityManager

    override fun getConfigByName(name: String?, guildId: String?): ConfigDto? {
        val q: Query = entityManager.createNamedQuery("ConfigDto.getValue", ConfigDto::class.java)
        q.setParameter("name", name)
        q.setParameter("guildId", guildId ?: "ALL")

        val allInclusiveConfig: List<ConfigDto?> = q.resultList as List<ConfigDto?>
        val serverSpecificConfig = allInclusiveConfig.filter { it?.guildId == guildId }
        return serverSpecificConfig.firstOrNull() ?: allInclusiveConfig.firstOrNull() ?: ConfigDto()
    }

    override fun listAllConfig(): List<ConfigDto?> {
        val q: Query = entityManager.createNamedQuery("ConfigDto.getAll", ConfigDto::class.java)
        return q.resultList as List<ConfigDto?>
    }

    override fun listGuildConfig(guildId: String?): List<ConfigDto?> {
        val q: Query = entityManager.createNamedQuery("ConfigDto.getGuildAll", ConfigDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList as List<ConfigDto?>
    }

    override fun createNewConfig(configDto: ConfigDto): ConfigDto {
        val databaseConfig = entityManager.find(ConfigDto::class.java, configDto)
        return if (databaseConfig == null || configDto.guildId != databaseConfig.guildId) {
            persistConfigDto(configDto)
        } else {
            databaseConfig
        }
    }

    override fun updateConfig(configDto: ConfigDto?): ConfigDto? {
        return configDto?.let {
            entityManager.merge(it)
            entityManager.flush()
            it
        }
    }

    override fun deleteAll(guildId: String?) {
        val deletionString = "DELETE FROM ConfigDto c WHERE c.guildId = :guildId"
        val query = entityManager.createQuery(deletionString)
        query.setParameter("guildId", guildId)
        query.executeUpdate()
    }

    override fun deleteConfig(guildId: String?, name: String?) {
        val deletionString = "DELETE FROM ConfigDto c WHERE c.guildId = :guildId AND c.name = :name"
        val query = entityManager.createQuery(deletionString)
        query.setParameter("guildId", guildId)
        query.setParameter("name", name)
        query.executeUpdate()
    }

    private fun persistConfigDto(configDto: ConfigDto): ConfigDto {
        entityManager.persist(configDto)
        entityManager.flush()
        return configDto
    }
}
