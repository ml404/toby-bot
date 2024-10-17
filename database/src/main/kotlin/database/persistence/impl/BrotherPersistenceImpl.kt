package database.persistence.impl

import database.persistence.IBrotherPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
open class BrotherPersistenceImpl : IBrotherPersistence {
    @PersistenceContext
    lateinit var entityManager: EntityManager


    override fun getBrotherById(discordId: Long?): database.dto.BrotherDto {
        return entityManager.find(database.dto.BrotherDto::class.java, discordId)
    }

    override fun getUserByName(name: String?): database.dto.BrotherDto {
        val q: Query = entityManager.createNamedQuery("BrotherDto.getName", database.dto.BrotherDto::class.java)
        q.setParameter("name", name)
        return q.singleResult as database.dto.BrotherDto
    }

    override fun updateBrother(brotherDto: database.dto.BrotherDto?): database.dto.BrotherDto? {
        entityManager.merge(brotherDto)
        entityManager.flush()
        return brotherDto
    }

    override fun listBrothers(): List<database.dto.BrotherDto?> {
        val q: Query = entityManager.createNamedQuery("BrotherDto.getAll", database.dto.BrotherDto::class.java)
        return q.resultList as List<database.dto.BrotherDto?>
    }


    override fun createNewBrother(brotherDto: database.dto.BrotherDto): database.dto.BrotherDto {
        val databaseBrother = entityManager.find(database.dto.BrotherDto::class.java, brotherDto.discordId)
        return if (databaseBrother == null) {
            persistBrotherDto(brotherDto)
        } else if (brotherDto.discordId != databaseBrother.discordId) {
            persistBrotherDto(brotherDto)
        } else databaseBrother
    }

    override fun deleteBrother(brotherDto: database.dto.BrotherDto?) {
        entityManager.remove(brotherDto)
        entityManager.flush()
    }

    override fun deleteBrotherById(discordId: Long?) {
        val q = entityManager.createNamedQuery("BrotherDto.deleteById")
        q.setParameter("discordId", discordId)
        q.executeUpdate()
    }

    private fun persistBrotherDto(brotherDto: database.dto.BrotherDto): database.dto.BrotherDto {
        entityManager.persist(brotherDto)
        entityManager.flush()
        return brotherDto
    }
}
