package database.service

import database.dto.MonsterTemplateDto

interface MonsterTemplateService {
    fun save(template: MonsterTemplateDto): MonsterTemplateDto
    fun getById(id: Long): MonsterTemplateDto?
    fun listByDm(dmDiscordId: Long): List<MonsterTemplateDto>
    fun deleteById(id: Long)
}
