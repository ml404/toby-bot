package toby.jpa.persistence;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import toby.jpa.dto.ConfigDto;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import java.util.List;
import java.util.stream.Collectors;

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
        q.setParameter("guild_id", guildId);

        List<ConfigDto> allInclusiveConfig = q.getResultList();
        List<ConfigDto> serverSpecificConfig = allInclusiveConfig.stream().filter(configDto -> configDto.getGuildId().equals(guildId)).collect(Collectors.toList());
        return !serverSpecificConfig.isEmpty() ? serverSpecificConfig.get(0) : !allInclusiveConfig.isEmpty() ? allInclusiveConfig.get(0) : new ConfigDto();

    }


    @Override
    @SuppressWarnings(value = "unchecked")
    public List<ConfigDto> listConfig() {
        Query q = em.createNamedQuery("ConfigDto.getAll", ConfigDto.class);
        return q.getResultList();
    }


    @Override
    public ConfigDto createNewConfig(ConfigDto configDto) {
        ConfigDto databaseConfig = em.find(ConfigDto.class, configDto);
        if (databaseConfig == null) {
            return persistConfigDto(configDto);
        } else if (!configDto.getGuildId().equals(databaseConfig.getGuildId())) {
            return persistConfigDto(configDto);
        } else
            return databaseConfig;
    }

    @Override
    public ConfigDto updateConfig(ConfigDto configDto) {
        em.merge(configDto);
        em.flush();
        return configDto;
    }

    public ConfigDto persistConfigDto(ConfigDto configDto) {
        em.persist(configDto);
        em.flush();
        return configDto;
    }
}

