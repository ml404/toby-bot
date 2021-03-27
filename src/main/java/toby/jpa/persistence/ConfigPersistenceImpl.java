package toby.jpa.persistence;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import toby.jpa.dto.ConfigDto;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Repository
@Transactional
public class ConfigPersistenceImpl  implements IConfigPersistence {

    @PersistenceContext
    protected EntityManager em;

    public EntityManager getEntityManager() {
        return em;
    }

    public void setEntityManager(EntityManager entityManager) {
        this.em = entityManager;
    }


    @Override
    public ConfigDto getConfigByName(String name, String guildId) {
        Query q = em.createNamedQuery("ConfigDto.getValue", ConfigDto.class);
        q.setParameter("name", name);
        q.setParameter("guild_id", guildId);
        List<ConfigDto> allInclusiveConfig = (List<ConfigDto>) q.getResultList();
        List<ConfigDto> serverSpecificConfig = allInclusiveConfig.stream().filter(configDto -> configDto.getGuildId().equals(guildId)).collect(Collectors.toList());
        return !serverSpecificConfig.isEmpty() ? serverSpecificConfig.get(0) : !allInclusiveConfig.isEmpty() ? allInclusiveConfig.get(0) : new ConfigDto();

    }


    @Override
    public ArrayList<ConfigDto> listConfig() {
        Query q = em.createNamedQuery("ConfigDto.getAll", ConfigDto.class);
        return (ArrayList<ConfigDto>) q.getResultList();
    }


    @Override
    public Long createNewConfig(ConfigDto configDto) {
        return null;
    }
}

