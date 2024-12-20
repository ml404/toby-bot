package database.persistence.impl

import database.dto.BrotherDto
import database.persistence.BrotherPersistence
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.TypedQuery
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional
open class DefaultBrotherPersistence : BrotherPersistence {
    @PersistenceContext
    lateinit var entityManager: EntityManager


    override fun getBrotherById(discordId: Long?): BrotherDto {
        return entityManager.find(BrotherDto::class.java, discordId)
    }

    override fun getUserByName(name: String?): BrotherDto {
        val q: TypedQuery<BrotherDto> = entityManager.createNamedQuery("BrotherDto.getName", BrotherDto::class.java)
        q.setParameter("name", name)
        return q.singleResult
    }

    override fun updateBrother(brotherDto: BrotherDto?): BrotherDto? {
        entityManager.merge(brotherDto)
        entityManager.flush()
        return brotherDto
    }

    override fun listBrothers(): List<BrotherDto?> {
        val q: TypedQuery<BrotherDto> = entityManager.createNamedQuery("BrotherDto.getAll", BrotherDto::class.java)
        return q.resultList
    }


    override fun createNewBrother(brotherDto: BrotherDto): BrotherDto {
        val databaseBrother = entityManager.find(BrotherDto::class.java, brotherDto.discordId)
        return if (databaseBrother == null) {
            persistBrotherDto(brotherDto)
        } else if (brotherDto.discordId != databaseBrother.discordId) {
            persistBrotherDto(brotherDto)
        } else databaseBrother
    }

    override fun deleteBrother(brotherDto: BrotherDto?) {
        entityManager.remove(brotherDto)
        entityManager.flush()
    }

    override fun deleteBrotherById(discordId: Long?) {
        val q = entityManager.createNamedQuery("BrotherDto.deleteById")
        q.setParameter("discordId", discordId)
        q.executeUpdate()
    }

    private fun persistBrotherDto(brotherDto: BrotherDto): BrotherDto {
        entityManager.persist(brotherDto)
        entityManager.flush()
        return brotherDto
    }
}
