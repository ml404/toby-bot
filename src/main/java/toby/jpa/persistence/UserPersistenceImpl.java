package toby.jpa.persistence;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import toby.jpa.dto.UserDto;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import java.util.List;

@Repository
@Transactional
public class UserPersistenceImpl implements IUserPersistence {

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
    public List<UserDto> listGuildUsers(Long guildId) {
        Query q = em.createNamedQuery("UserDto.getGuildAll", UserDto.class);
        q.setParameter("guildId", guildId);
        return q.getResultList();
    }

    @Override
    public UserDto createNewUser(UserDto userDto) {
        UserDto databaseConfig = em.find(UserDto.class, userDto);
        return (databaseConfig == null) ? persistConfigDto(userDto) : databaseConfig;
    }

    @Override
    public UserDto getUserById(Long discordId, Long guildId) {
        Query q = em.createNamedQuery("UserDto.getById", UserDto.class);
        q.setParameter("discordId", discordId);
        q.setParameter("guildId", guildId);
        return (UserDto) q.getSingleResult();
    }

    @Override
    public UserDto updateUser(UserDto userDto) {
        em.merge(userDto);
        em.flush();
        return userDto;
    }

    @Override
    public void deleteUser(UserDto userDto) {
        em.remove(userDto);
        em.flush();
    }

    @Override
    public void deleteUserById(Long discordId, Long guildId) {
        Query q = em.createNamedQuery("UserDto.deleteById");
        q.setParameter("discordId", discordId);
        q.setParameter("guildId", guildId);
        q.executeUpdate();

    }

    private UserDto persistConfigDto(UserDto userDto) {
        em.persist(userDto);
        em.flush();
        return userDto;
    }
}
