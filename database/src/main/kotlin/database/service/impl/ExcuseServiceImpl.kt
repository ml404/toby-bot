package database.service.impl

import database.dto.ExcuseDto
import database.persistence.IExcusePersistence
import database.service.IExcuseService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

@Service
open class ExcuseServiceImpl : IExcuseService {
    @Autowired
    lateinit var excuseService: IExcusePersistence

    @Cacheable(value = ["excuses"])
    override fun listAllGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        return excuseService.listAllGuildExcuses(guildId)
    }

    @Cacheable(value = ["excuses"])
    override fun listApprovedGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        return excuseService.listApprovedGuildExcuses(guildId)
    }

    override fun listPendingGuildExcuses(guildId: Long?): List<ExcuseDto?> {
        return excuseService.listPendingGuildExcuses(guildId)
    }

    @CachePut(value = ["excuses"], key = "#excuseDto.id")
    override fun createNewExcuse(excuseDto: ExcuseDto?): ExcuseDto? {
        return excuseService.createNewExcuse(excuseDto)
    }

    @CachePut(value = ["excuses"], key = "#id")
    override fun getExcuseById(id: Long?): ExcuseDto {
        return excuseService.getExcuseById(id)
    }

    @CachePut(value = ["excuses"], key = "#excuseDto.id")
    override fun updateExcuse(excuseDto: ExcuseDto): ExcuseDto {
        return excuseService.updateExcuse(excuseDto)
    }

    @CacheEvict(value = ["excuses"], allEntries = true)
    override fun deleteExcuseByGuildId(guildId: Long?) {
        excuseService.deleteAllExcusesForGuild(guildId)
    }

    @CacheEvict(value = ["excuses"], key = "#id")
    override fun deleteExcuseById(id: Long?) {
        excuseService.deleteExcuseById(id)
    }

    @CacheEvict(value = ["excuses"], allEntries = true)
    override fun clearCache() {
    }
}
