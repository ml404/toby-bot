package toby.jpa.persistence;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import toby.jpa.dto.BrotherDto;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import java.util.ArrayList;

@Repository
@Transactional
public class BrotherPersistenceImpl implements IBrotherPersistence {

    @PersistenceContext
    protected EntityManager em;

    public EntityManager getEntityManager() {
        return em;
    }

    public void setEntityManager(EntityManager entityManager) {
        this.em = entityManager;
    }


    @Override
    public BrotherDto getBrotherById(Long discordId) {
        return em.find(BrotherDto.class, discordId);
    }

    @Override
    public BrotherDto getUserByName(String name) {
        Query q = em.createNamedQuery("BrotherDto.getName", BrotherDto.class);
        q.setParameter("name", name);
        return (BrotherDto) q.getSingleResult();
    }

    @Override
    public ArrayList<BrotherDto> listBrothers() {
        Query q = em.createNamedQuery("BrotherDto.getAll", BrotherDto.class);
        return (ArrayList<BrotherDto>) q.getResultList();
    }


    @Override
    public Long createNewBrother(BrotherDto brotherDto) {
        return null;
    }


}
