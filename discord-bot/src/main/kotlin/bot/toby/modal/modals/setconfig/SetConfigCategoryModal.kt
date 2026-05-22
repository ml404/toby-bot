package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.ValidationOutcome
import core.modal.Modal
import core.modal.ModalContext
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.selections.EntitySelectMenu
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.modals.Modal as JdaModal

/**
 * Base for every `/setconfig <category>` modal. Owns the
 * read-fields → validate-all → write-all-or-error flow so each
 * concrete category just declares its spec map + field-id map.
 *
 * Channel-typed fields ([FieldSpec.ChannelByIdStoreName] /
 * [FieldSpec.ChannelByIdStoreId]) render as Discord native channel
 * pickers ([EntitySelectMenu]) instead of typed-id [TextInput] fields.
 * Owners click their channel from a list; no IDs typed.
 *
 * Subclasses override [afterWrites] when they need a side effect
 * keyed off an upsert result (e.g. ACTIVITY_TRACKING's first-enable
 * notifier).
 */
abstract class SetConfigCategoryModal(
    protected val configService: ConfigService,
) : Modal {

    /**
     * Modal title shown in the Discord client. ≤45 chars. Takes the
     * full modal id (`<name>` for simple modals, `<name>:<suffix>`
     * for parameterised ones like the per-game stakes modal) so
     * subclasses can vary the title by id.
     */
    protected abstract fun titleFor(modalId: String): String

    /**
     * Insertion-ordered list of (config key, field spec) for the
     * given modal id. The order is the visual order of fields and
     * the order error messages appear in on validation failure.
     */
    protected abstract fun specsFor(modalId: String): LinkedHashMap<Configurations, FieldSpec>

    /**
     * Discord-side field id (TextInput / EntitySelectMenu component
     * id) for each config key. Must match the lookup keys used by
     * `event.getValue(...)`.
     */
    protected abstract fun fieldIdsFor(modalId: String): Map<Configurations, String>

    /**
     * Hook fired after the batch upsert completes. Receives the list
     * of (key, UpsertResult) so subclasses can branch on Created vs
     * Updated.previousValue. Default no-op.
     */
    protected open fun afterWrites(
        ctx: ModalContext,
        results: List<Pair<Configurations, ConfigService.UpsertResult>>,
    ) = Unit

    final override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guild = ctx.guild
        val modalId = event.modalId
        val specs = specsFor(modalId)
        val fieldIds = fieldIdsFor(modalId)

        // Channel-picker fields submit their value via `asLongList`;
        // text fields use `asString`. Branch on the spec so the
        // validator receives a single uniform String to parse.
        val readField: (Configurations) -> String? = { key ->
            val fieldId = fieldIds.getValue(key)
            when (specs.getValue(key)) {
                is FieldSpec.ChannelByIdStoreName,
                is FieldSpec.ChannelByIdStoreId,
                    -> event.getValue(fieldId)?.asLongList?.firstOrNull()?.toString()
                else -> event.getValue(fieldId)?.asString
            }
        }

        val outcome = SetConfigFieldValidator.validateAll(
            readField = readField,
            specs = specs,
            guild = guild,
        )

        when (outcome) {
            is ValidationOutcome.Errors -> {
                event.hook.sendMessage(
                    "Couldn't save — fix these fields:\n" +
                        outcome.messages.joinToString("\n") { "• $it" } +
                        "\n\nNo changes were written."
                ).setEphemeral(true).queue()
            }

            is ValidationOutcome.Writes -> {
                if (outcome.pairs.isEmpty()) {
                    event.hook.sendMessage("No fields filled — nothing changed.")
                        .setEphemeral(true).queue()
                    return
                }
                // One transactional batch instead of N sequential commits.
                // Pair the (key, UpsertResult) results back up by index so
                // the existing `afterWrites` hook (which keys side-effects
                // off the Configurations enum) still works.
                val rows = outcome.pairs.map { (key, value) -> key.configValue to value }
                val upsertResults = configService.upsertAll(guild.id, rows)
                val results = outcome.pairs.zip(upsertResults) { (key, _), result -> key to result }
                event.hook.sendMessage(buildSummary(outcome.pairs, specs))
                    .setEphemeral(true).queue()
                afterWrites(ctx, results)
            }
        }
    }

    /**
     * Build the modal pre-populated with current values. The caller
     * supplies [modalId] (e.g. `"setconfig_stakes:dice"`) so
     * parameterised modals can vary their fields per-call; for
     * simple modals it's the bare [name].
     *
     * [guild] is needed to pre-resolve channel-picker default values
     * — `MOVE` stores a channel name, so we look it up to recover the
     * channel id for [EntitySelectMenu.Builder.setDefaultValues]. The
     * other channel-picker fields store the id directly.
     *
     * [currentValues] is read once by the caller and passed in as a
     * lookup — the modal handler does no DB reads at open time.
     */
    fun buildModal(
        modalId: String,
        guild: Guild,
        currentValues: (Configurations) -> String?,
    ): JdaModal {
        val title = titleFor(modalId)
        val specs = specsFor(modalId)
        val fieldIds = fieldIdsFor(modalId)
        val builder = JdaModal.create(modalId, title)
        for ((key, spec) in specs) {
            val fieldId = fieldIds.getValue(key)
            val current = currentValues(key)
            val component = when (spec) {
                is FieldSpec.ChannelByIdStoreName ->
                    buildChannelPicker(fieldId, ChannelType.VOICE, current, guild, storeMode = ChannelStore.BY_NAME)
                is FieldSpec.ChannelByIdStoreId ->
                    buildChannelPicker(fieldId, ChannelType.TEXT, current, guild, storeMode = ChannelStore.BY_ID)
                else -> buildTextInput(fieldId, spec, current)
            }
            builder.addComponents(Label.of(truncateLabel(spec.label), component))
        }
        return builder.build()
    }

    private fun buildTextInput(fieldId: String, spec: FieldSpec, current: String?): TextInput =
        TextInput.create(fieldId, TextInputStyle.SHORT)
            .setRequired(false)
            .apply {
                current?.takeIf { it.isNotEmpty() }?.let { setValue(it) }
                placeholderFor(spec)?.let { setPlaceholder(it) }
            }
            .build()

    private enum class ChannelStore { BY_NAME, BY_ID }

    /**
     * Build a channel-picker [EntitySelectMenu] filtered to
     * [channelType]. Pre-populates the picker's default value from
     * [current] — interpreting it as a channel name when [storeMode]
     * is [ChannelStore.BY_NAME], else as a channel id string.
     *
     * No selection = skip the write (matches the existing
     * "blank means don't overwrite" convention on text fields).
     */
    private fun buildChannelPicker(
        fieldId: String,
        channelType: ChannelType,
        current: String?,
        guild: Guild,
        storeMode: ChannelStore,
    ): EntitySelectMenu {
        val builder = EntitySelectMenu.create(fieldId, EntitySelectMenu.SelectTarget.CHANNEL)
            .setChannelTypes(channelType)
            .setRequiredRange(0, 1)
        val resolvedId: Long? = current?.takeIf { it.isNotEmpty() }?.let { value ->
            when (storeMode) {
                ChannelStore.BY_NAME -> when (channelType) {
                    ChannelType.VOICE -> guild.getVoiceChannelsByName(value, true).firstOrNull()?.idLong
                    ChannelType.TEXT -> guild.getTextChannelsByName(value, true).firstOrNull()?.idLong
                    else -> null
                }
                ChannelStore.BY_ID -> value.toLongOrNull()?.takeIf { guildHasChannel(guild, channelType, it) }
            }
        }
        if (resolvedId != null) {
            builder.setDefaultValues(EntitySelectMenu.DefaultValue.channel(resolvedId))
        }
        return builder.build()
    }

    private fun guildHasChannel(guild: Guild, channelType: ChannelType, id: Long): Boolean = when (channelType) {
        ChannelType.TEXT -> guild.getTextChannelById(id) != null
        ChannelType.VOICE -> guild.getVoiceChannelById(id) != null
        else -> false
    }

    private fun buildSummary(
        pairs: List<Pair<Configurations, String>>,
        specs: LinkedHashMap<Configurations, FieldSpec>,
    ): String {
        val header = "Saved ${pairs.size} setting${if (pairs.size == 1) "" else "s"}:\n"
        val body = pairs.joinToString("\n") { (key, value) ->
            "• ${specs.getValue(key).label}: $value"
        }
        return header + body
    }

    private fun placeholderFor(spec: FieldSpec): String? = when (spec) {
        is FieldSpec.IntRange -> "${spec.range.first}–${spec.range.last}"
        is FieldSpec.LongMin -> "≥ ${spec.min}"
        is FieldSpec.DoubleRange -> "${spec.range.start}–${spec.range.endInclusive}"
        is FieldSpec.BoolStrict -> "true / false"
        is FieldSpec.EnumChoice -> spec.allowed.joinToString(" / ")
        // Channel specs render as EntitySelectMenu, not TextInput — no placeholder applies.
        is FieldSpec.ChannelByIdStoreName,
        is FieldSpec.ChannelByIdStoreId,
            -> null
    }

    private fun truncateLabel(label: String): String =
        if (label.length <= LABEL_LIMIT) label else label.take(LABEL_LIMIT - 1) + "…"

    companion object {
        /** Discord modal label cap. */
        private const val LABEL_LIMIT = 45
    }
}
