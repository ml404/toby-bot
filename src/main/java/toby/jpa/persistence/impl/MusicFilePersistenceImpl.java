package toby.jpa.persistence.impl;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import toby.jpa.dto.MusicDto;
import toby.jpa.persistence.IMusicFilePersistence;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

@Repository
@Transactional
public class MusicFilePersistenceImpl implements IMusicFilePersistence {

    @PersistenceContext
    protected EntityManager em;

    public EntityManager getEntityManager() {
        return em;
    }

    public void setEntityManager(EntityManager entityManager) {
        this.em = entityManager;
    }


    private MusicDto persistMusicDto(MusicDto musicDto) {
        em.persist(musicDto);
        em.flush();
        return musicDto;
    }

    @Override
    public MusicDto createNewMusicFile(MusicDto musicDto) {
        MusicDto databaseMusicFile = em.find(MusicDto.class, musicDto.getId());
        return (databaseMusicFile == null) ? persistMusicDto(musicDto) : updateMusicFile(musicDto);
    }

    @Override
    public MusicDto getMusicFileById(String id) {
        Query q = em.createNamedQuery("MusicDto.getById", MusicDto.class);
        q.setParameter("id", id);
        return (MusicDto) q.getSingleResult();
    }

    @Override
    public MusicDto updateMusicFile(MusicDto musicDto) {
        em.merge(musicDto);
        em.flush();
        return musicDto;
    }

    @Override
    public void deleteMusicFile(MusicDto musicDto) {
        em.remove(musicDto);
        em.flush();
    }

    @Override
    public void deleteMusicFileById(String id) {
        Query q = em.createNamedQuery("MusicDto.deleteById");
        q.setParameter("id", id);
        q.executeUpdate();
    }
}
