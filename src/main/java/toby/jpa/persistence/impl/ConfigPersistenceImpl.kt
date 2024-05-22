package toby.jpa.persistence.impl;

import jakarta.persistence.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import toby.jpa.dto.ConfigDto;
import toby.jpa.persistence.IConfigPersistence;

import java.util.List;

@Repository
@Transactional
public class ConfigPersistenceImpl implements IConfigPersistence {

    @PersistenceContext
    protected EntityManager em;

    public EntityManager getEntityManager() {
        return em;
    }

    public void setEntityManager(EntityManager entityManager) {
        this.em = entityManager;
    }


    @Override
    @SuppressWarnings(value = "unchecked")
    public ConfigDto getConfigByName(String name, String guildId) {
        Query q = em.createNamedQuery("ConfigDto.getValue", ConfigDto.class);
        q.setParameter("name", name);
        q.setParameter("guildId", guildId);

        List<ConfigDto> allInclusiveConfig = q.getResultList();
        List<ConfigDto> serverSpecificConfig = allInclusiveConfig.stream().filter(configDto -> configDto.getGuildId().equals(guildId)).toList();
        return !serverSpecificConfig.isEmpty() ? serverSpecificConfig.get(0) : !allInclusiveConfig.isEmpty() ? allInclusiveConfig.get(0) : new ConfigDto();

    }


    @Override
    @SuppressWarnings(value = "unchecked")
    public List<ConfigDto> listAllConfig() {
        Query q = em.createNamedQuery("ConfigDto.getAll", ConfigDto.class);
        return q.getResultList();
    }

    @Override
    @SuppressWarnings(value = "unchecked")
    public List<ConfigDto> listGuildConfig(String guildId) {
        Query q = em.createNamedQuery("ConfigDto.getGuildAll", ConfigDto.class);
        q.setParameter("guildId", guildId);
        return q.getResultList();
    }


    @Override
    public ConfigDto createNewConfig(ConfigDto configDto) {
        ConfigDto databaseConfig = em.find(ConfigDto.class, configDto);
        if (databaseConfig == null) {
            return persistConfigDto(configDto);
        } else if (!configDto.getGuildId().equals(databaseConfig.getGuildId())) {
            return persistConfigDto(configDto);
        } else return databaseConfig;
    }

    @Override
    public ConfigDto updateConfig(ConfigDto configDto) {
        em.merge(configDto);
        em.flush();
        return configDto;
    }

    @Override
    public void deleteAll(String guildId) {
        String deletionString = "DELETE FROM ConfigDto c WHERE c.guildId = :guildId";
        Query query = em.createQuery(deletionString);
        query.setParameter("guildId", guildId);
        query.executeUpdate();
    }

    @Override
    public void deleteConfig(String guildId, String name) {
        String deletionString = "DELETE FROM ConfigDto c WHERE c.guildId = :guildId AND c.name = :name";
        Query query = em.createQuery(deletionString);
        query.setParameter("guildId", guildId);
        query.setParameter("name", name);
        query.executeUpdate();
    }

    private ConfigDto persistConfigDto(ConfigDto configDto) {
        em.persist(configDto);
        em.flush();
        return configDto;
    }
}

