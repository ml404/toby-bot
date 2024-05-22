package toby.jpa.persistence.impl

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import toby.jpa.dto.ExcuseDto
import toby.jpa.persistence.IExcusePersistence

@Repository
@Transactional
open class ExcusePersistenceImpl internal constructor() : IExcusePersistence {
    @PersistenceContext
    var entityManager: EntityManager? = null


    override fun listAllGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        val q: Query = entityManager!!.createNamedQuery("ExcuseDto.getAll", ExcuseDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList as List<ExcuseDto?>
    }

    override fun listApprovedGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        val q: Query = entityManager!!.createNamedQuery("ExcuseDto.getApproved", ExcuseDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList as List<ExcuseDto?>
    }

    override fun listPendingGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        val q: Query = entityManager!!.createNamedQuery("ExcuseDto.getPending", ExcuseDto::class.java)
        q.setParameter("guildId", guildId)
        return q.resultList as List<ExcuseDto?>
    }

    override fun createNewExcuse(excuseDto: ExcuseDto?): ExcuseDto? {
        return persistExcuseDto(excuseDto)
    }


    override fun getExcuseById(id: Int?): ExcuseDto {
        val excuseQuery: Query = entityManager!!.createNamedQuery("ExcuseDto.getById", ExcuseDto::class.java)
        excuseQuery.setParameter("id", id)
        return excuseQuery.singleResult as ExcuseDto
    }

    override fun updateExcuse(excuseDto: ExcuseDto): ExcuseDto {
        val dbExcuse = getExcuseById(excuseDto.id)

        if (excuseDto != dbExcuse) {
            entityManager!!.merge(excuseDto)
            entityManager!!.flush()
        }

        return excuseDto
    }

    override fun deleteAllExcusesForGuild(guildId: Long?) {
        val excuseQuery = entityManager!!.createNamedQuery("ExcuseDto.deleteAllByGuildId")
        excuseQuery.setParameter("guildId", guildId)
        excuseQuery.executeUpdate()
    }

    override fun deleteExcuseById(id: Int?) {
        val excuseQuery = entityManager!!.createNamedQuery("ExcuseDto.deleteById")
        excuseQuery.setParameter("id", id)
        excuseQuery.executeUpdate()
    }

    @Transactional
    private fun persistExcuseDto(excuseDto: ExcuseDto?): ExcuseDto? {
        entityManager!!.persist(excuseDto)
        entityManager!!.flush()
        return excuseDto
    }
}
