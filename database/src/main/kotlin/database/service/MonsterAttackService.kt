package database.service

import database.dto.MonsterAttackDto

interface MonsterAttackService {
    fun save(attack: MonsterAttackDto): MonsterAttackDto
    fun getById(id: Long): MonsterAttackDto?
    fun listByTemplate(templateId: Long): List<MonsterAttackDto>
    fun countByTemplate(templateId: Long): Long
    fun deleteById(id: Long)
}
