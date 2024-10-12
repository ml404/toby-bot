package bot.database.persistence.impl

import bot.database.persistence.IBrotherPersistence
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


    override fun getBrotherById(discordId: Long?): bot.database.dto.BrotherDto {
        return entityManager.find(bot.database.dto.BrotherDto::class.java, discordId)
    }

    override fun getUserByName(name: String?): bot.database.dto.BrotherDto {
        val q: Query = entityManager.createNamedQuery("BrotherDto.getName", bot.database.dto.BrotherDto::class.java)
        q.setParameter("name", name)
        return q.singleResult as bot.database.dto.BrotherDto
    }

    override fun updateBrother(brotherDto: bot.database.dto.BrotherDto?): bot.database.dto.BrotherDto? {
        entityManager.merge(brotherDto)
        entityManager.flush()
        return brotherDto
    }

    override fun listBrothers(): List<bot.database.dto.BrotherDto?> {
        val q: Query = entityManager.createNamedQuery("BrotherDto.getAll", bot.database.dto.BrotherDto::class.java)
        return q.resultList as List<bot.database.dto.BrotherDto?>
    }


    override fun createNewBrother(brotherDto: bot.database.dto.BrotherDto): bot.database.dto.BrotherDto {
        val databaseBrother = entityManager.find(bot.database.dto.BrotherDto::class.java, brotherDto.discordId)
        return if (databaseBrother == null) {
            persistBrotherDto(brotherDto)
        } else if (brotherDto.discordId != databaseBrother.discordId) {
            persistBrotherDto(brotherDto)
        } else databaseBrother
    }

    override fun deleteBrother(brotherDto: bot.database.dto.BrotherDto?) {
        entityManager.remove(brotherDto)
        entityManager.flush()
    }

    override fun deleteBrotherById(discordId: Long?) {
        val q = entityManager.createNamedQuery("BrotherDto.deleteById")
        q.setParameter("discordId", discordId)
        q.executeUpdate()
    }

    private fun persistBrotherDto(brotherDto: bot.database.dto.BrotherDto): bot.database.dto.BrotherDto {
        entityManager.persist(brotherDto)
        entityManager.flush()
        return brotherDto
    }
}
