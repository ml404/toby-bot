package database.service.impl

import database.dto.MonsterAttackDto
import database.persistence.MonsterAttackPersistence
import database.service.MonsterAttackService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DefaultMonsterAttackService(
    private val monsterAttackPersistence: MonsterAttackPersistence
) : MonsterAttackService {

    override fun save(attack: MonsterAttackDto): MonsterAttackDto =
        monsterAttackPersistence.save(attack)

    override fun getById(id: Long): MonsterAttackDto? =
        monsterAttackPersistence.getById(id)

    override fun listByTemplate(templateId: Long): List<MonsterAttackDto> =
        monsterAttackPersistence.listByTemplate(templateId)

    override fun countByTemplate(templateId: Long): Long =
        monsterAttackPersistence.countByTemplate(templateId)

    override fun deleteById(id: Long) =
        monsterAttackPersistence.deleteById(id)
}
