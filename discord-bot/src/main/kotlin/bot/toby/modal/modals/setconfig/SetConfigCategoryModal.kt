package bot.toby.modal.modals.setconfig

import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.FieldSpec
import bot.toby.modal.modals.setconfig.SetConfigFieldValidator.ValidationOutcome
import core.modal.Modal
import core.modal.ModalContext
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.modals.Modal as JdaModal

/**
 * Base for every `/setconfig <category>` modal. Owns the
 * read-fields → validate-all → write-all-or-error flow so each
 * concrete category just declares its spec map + field-id map.
 *
 * All fields render as [TextInput] inside a [Label]. Discord modals
 * do not support mixing `TextInput` and `EntitySelectMenu` in a
 * single modal — an earlier attempt to render channel fields as
 * native channel pickers ([EntitySelectMenu]) inside this modal made
 * the entire payload fail to open with "Interaction failed". Picker
 * UX for channel fields lives in dedicated channel-only modals (e.g.
 * `InstallQuickChannelsModal`) reached from the install wizard.
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
     * Discord-side field id (TextInput component id) for each config
     * key. Must match the lookup keys used by `event.getValue(...)`.
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

        val outcome = SetConfigFieldValidator.validateAll(
            readField = { key -> event.getValue(fieldIds.getValue(key))?.asString },
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
     * [guild] is currently unused — kept on the signature so future
     * picker-modal experiments can resolve channel ids without
     * breaking every caller. (See class doc for why mixed-component
     * modals don't work.)
     *
     * [currentValues] is read once by the caller and passed in as a
     * lookup — the modal handler does no DB reads at open time.
     */
    @Suppress("UNUSED_PARAMETER")
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
            val input = TextInput.create(fieldIds.getValue(key), TextInputStyle.SHORT)
                .setRequired(false)
                .apply {
                    currentValues(key)?.takeIf { it.isNotEmpty() }?.let { setValue(it) }
                    placeholderFor(spec)?.let { setPlaceholder(it) }
                }
                .build()
            builder.addComponents(Label.of(truncateLabel(spec.label), input))
        }
        return builder.build()
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
        is FieldSpec.ChannelByIdStoreName -> "channel id"
        is FieldSpec.ChannelByIdStoreId -> if (spec.allowClear) "channel id (0 to clear)" else "channel id"
    }

    private fun truncateLabel(label: String): String =
        if (label.length <= LABEL_LIMIT) label else label.take(LABEL_LIMIT - 1) + "…"

    companion object {
        /** Discord modal label cap. */
        private const val LABEL_LIMIT = 45
    }
}
