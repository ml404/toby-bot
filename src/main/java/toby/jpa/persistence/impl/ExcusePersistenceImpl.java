package toby.jpa.persistence.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import toby.jpa.dto.ExcuseDto;
import toby.jpa.persistence.IExcusePersistence;

import java.util.List;

@Repository
@Transactional
public class ExcusePersistenceImpl implements IExcusePersistence {

    ExcusePersistenceImpl() {
    }

    @PersistenceContext
    protected EntityManager em;

    public EntityManager getEntityManager() {
        return em;
    }

    public void setEntityManager(EntityManager entityManager) {
        this.em = entityManager;
    }


    @Override
    @SuppressWarnings("unchecked")
    public List<ExcuseDto> listAllGuildExcuses(Long guildId) {
        Query q = em.createNamedQuery("ExcuseDto.getAll", ExcuseDto.class);
        q.setParameter("guildId", guildId);
        return q.getResultList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ExcuseDto> listApprovedGuildExcuses(Long guildId) {
        Query q = em.createNamedQuery("ExcuseDto.getApproved", ExcuseDto.class);
        q.setParameter("guildId", guildId);
        return q.getResultList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ExcuseDto> listPendingGuildExcuses(Long guildId) {
        Query q = em.createNamedQuery("ExcuseDto.getPending", ExcuseDto.class);
        q.setParameter("guildId", guildId);
        return q.getResultList();
    }

    @Override
    public ExcuseDto createNewExcuse(ExcuseDto excuseDto) {
        return persistExcuseDto(excuseDto);
    }


    @Override
    public ExcuseDto getExcuseById(Integer id) {
        Query excuseQuery = em.createNamedQuery("ExcuseDto.getById", ExcuseDto.class);
        excuseQuery.setParameter("id", id);
        return (ExcuseDto) excuseQuery.getSingleResult();
    }

    @Override
    public ExcuseDto updateExcuse(ExcuseDto excuseDto) {
        ExcuseDto dbExcuse = getExcuseById(excuseDto.getId());

        if (!excuseDto.equals(dbExcuse)) {
            em.merge(excuseDto);
            em.flush();
        }

        return excuseDto;
    }

    @Override
    public void deleteAllExcusesForGuild(Long guildId) {
        Query excuseQuery = em.createNamedQuery("ExcuseDto.deleteAllByGuildId");
        excuseQuery.setParameter("guildId", guildId);
        excuseQuery.executeUpdate();
    }

    @Override
    public void deleteExcuseById(Integer id) {
        Query excuseQuery = em.createNamedQuery("ExcuseDto.deleteById");
        excuseQuery.setParameter("id", id);
        excuseQuery.executeUpdate();

    }

    @Transactional
    private ExcuseDto persistExcuseDto(ExcuseDto excuseDto) {
        em.persist(excuseDto);
        em.flush();
        return excuseDto;
    }
}
