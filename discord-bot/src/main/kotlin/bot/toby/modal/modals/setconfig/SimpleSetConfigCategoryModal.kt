package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.ConfigDto.Configurations
import database.service.guild.ConfigService

/**
 * Sub-base for `/setconfig` modals whose title + spec list + field-id
 * map are constant (don't vary by modal id). 10 of the 11 modals fall
 * into this category; only [SetConfigStakesModal] needs to vary its
 * fields per-game and so extends [SetConfigCategoryModal] directly.
 */
abstract class SimpleSetConfigCategoryModal(
    configService: ConfigService,
) : SetConfigCategoryModal(configService) {

    protected abstract val title: String
    protected abstract val specs: LinkedHashMap<Configurations, FieldSpec>
    protected abstract val fieldIds: Map<Configurations, String>

    final override fun titleFor(modalId: String): String = title
    final override fun specsFor(modalId: String): LinkedHashMap<Configurations, FieldSpec> = specs
    final override fun fieldIdsFor(modalId: String): Map<Configurations, String> = fieldIds
}
