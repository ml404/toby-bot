package toby.jpa.persistence.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import toby.jpa.dto.BrotherDto;
import toby.jpa.persistence.IBrotherPersistence;

import java.util.List;

@Repository
@Transactional
public class BrotherPersistenceImpl implements IBrotherPersistence  {

@PersistenceContext
protected EntityManager em;

public EntityManager getEntityManager(){
        return em;
        }

public void setEntityManager(EntityManager entityManager){
        this.em=entityManager;
        }


@Override
public BrotherDto getBrotherById(Long discordId){
        return em.find(BrotherDto.class,discordId);
        }

@Override
public BrotherDto getUserByName(String name){
        Query q=em.createNamedQuery("BrotherDto.getName",BrotherDto.class);
        q.setParameter("name",name);
        return(BrotherDto)q.getSingleResult();
        }

@Override
public BrotherDto updateBrother(BrotherDto brotherDto){
        em.merge(brotherDto);
        em.flush();
        return brotherDto;
        }

@Override
@SuppressWarnings("unchecked")
public List<BrotherDto> listBrothers(){
        Query q=em.createNamedQuery("BrotherDto.getAll",BrotherDto.class);
        return q.getResultList();
        }


@Override
public BrotherDto createNewBrother(BrotherDto brotherDto){

        BrotherDto databaseBrother=em.find(BrotherDto.class,brotherDto.getDiscordId());
        if(databaseBrother==null){
        return persistBrotherDto(brotherDto);
        }else if(!brotherDto.getDiscordId().equals(databaseBrother.getDiscordId())){
        return persistBrotherDto(brotherDto);
        }else
        return databaseBrother;
        }

@Override
public void deleteBrother(BrotherDto brotherDto){
        em.remove(brotherDto);
        em.flush();
        }

@Override
public void deleteBrotherById(Long discordId){
        Query q=em.createNamedQuery("BrotherDto.deleteById");
        q.setParameter("discordId",discordId);
        q.executeUpdate();
        }

private BrotherDto persistBrotherDto(BrotherDto brotherDto){
        em.persist(brotherDto);
        em.flush();
        return brotherDto;
        }
        }
