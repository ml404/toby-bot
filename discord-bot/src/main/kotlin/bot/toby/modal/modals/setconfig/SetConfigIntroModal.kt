package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import database.dto.guild.ConfigDto.Configurations
import database.service.guild.ConfigService
import org.springframework.stereotype.Component

/**
 * `/setconfig intro` — everything a server can decide about intros.
 *
 * `DEFAULT_INTRO_VOLUME` used to be the only intro key, and it lived under
 * `general` alongside the unrelated audio and channel settings. That left a
 * server able to make intros quieter but never to turn them off, and never to
 * exempt a channel — so the "study" or "meeting" voice channel got everyone's
 * intro at full volume and the only remedy was asking members one by one to
 * unset theirs. The volume key moves here so the intro settings are in one
 * place, which also frees a slot in the general modal (Discord caps modals at
 * five fields).
 */
@Component
class SetConfigIntroModal(
    configService: ConfigService,
) : SimpleSetConfigCategoryModal(configService) {

    override val name = MODAL_NAME
    override val title = "Intro settings"

    override val specs: LinkedHashMap<Configurations, FieldSpec> = linkedMapOf(
        Configurations.INTROS_ENABLED to FieldSpec.BoolStrict("Intros enabled"),
        Configurations.INTRO_VOLUME to FieldSpec.IntRange("Default intro volume", 0..200),
        Configurations.INTRO_NORMALISE_VOLUME to FieldSpec.BoolStrict("Match intro loudness"),
        Configurations.INTRO_EXCLUDED_CHANNELS to FieldSpec.VoiceChannelIdList("Silent channels (ids)"),
    )

    override val fieldIds: Map<Configurations, String> = mapOf(
        Configurations.INTROS_ENABLED to FIELD_INTROS_ENABLED,
        Configurations.INTRO_VOLUME to FIELD_INTRO_VOLUME,
        Configurations.INTRO_NORMALISE_VOLUME to FIELD_NORMALISE,
        Configurations.INTRO_EXCLUDED_CHANNELS to FIELD_EXCLUDED_CHANNELS,
    )

    companion object {
        const val MODAL_NAME = "setconfig_intro"
        const val FIELD_INTROS_ENABLED = "intros_enabled"
        const val FIELD_INTRO_VOLUME = "intro_volume"
        const val FIELD_NORMALISE = "intro_normalise"
        const val FIELD_EXCLUDED_CHANNELS = "intro_excluded_channels"
    }
}
