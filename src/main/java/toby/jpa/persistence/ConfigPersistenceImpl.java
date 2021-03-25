package toby.jpa.persistence;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import toby.jpa.dto.ConfigDto;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import java.util.ArrayList;

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
    public ConfigDto getConfigByName(String name) {
        return em.find(ConfigDto.class, name);
    }

    public ConfigDto getByName(String name) {
        Query q = em.createNamedQuery("ConfigDto.getName", ConfigDto.class);
        q.setParameter("name", name);
        return (ConfigDto) q.getSingleResult();
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

