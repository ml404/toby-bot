package toby.jpa.persistence.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import toby.jpa.dto.MusicDto;
import toby.jpa.persistence.IMusicFilePersistence;


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
        // Create a native SQL query to retrieve the size of the music_blob column
        String sql = "SELECT LENGTH(music_blob) AS data_size FROM music_files WHERE id = :id";

        Query sizeQ = em.createNativeQuery(sql);
        sizeQ.setParameter("id", id);

        // Execute the query
        Object result = sizeQ.getSingleResult();

        // Handle the result (assuming it's a Long)
        if (result instanceof Long dataSize) {
            System.out.println("Data size in bytes: " + dataSize);
        }

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
