package database.service.impl

import database.dto.MonsterTemplateDto
import database.persistence.MonsterTemplatePersistence
import database.service.MonsterTemplateService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DefaultMonsterTemplateService(
    private val monsterTemplatePersistence: MonsterTemplatePersistence
) : MonsterTemplateService {

    override fun save(template: MonsterTemplateDto): MonsterTemplateDto =
        monsterTemplatePersistence.save(template)

    override fun getById(id: Long): MonsterTemplateDto? =
        monsterTemplatePersistence.getById(id)

    override fun listByDm(dmDiscordId: Long): List<MonsterTemplateDto> =
        monsterTemplatePersistence.listByDm(dmDiscordId)

    override fun deleteById(id: Long) =
        monsterTemplatePersistence.deleteById(id)
}
