package bot.toby.modal.modals.setconfig

import database.dto.guild.ConfigDto
import net.dv8tion.jda.api.entities.Guild

/**
 * Shared typed-field parser/validator for the `/setconfig …` modals.
 *
 * Each modal declares a `LinkedHashMap<Configurations, FieldSpec>`
 * describing its fields, then calls [validateAll] with a reader that
 * looks up the user-submitted text by config key. The validator
 * returns either a list of (key, stringified-value) pairs to write or
 * a list of human-readable error messages — never a mix.
 *
 * Three-state per-field outcome:
 *   - `Skip`  — input was blank/whitespace → no upsert
 *               (preserves the "blank means don't overwrite" rule)
 *   - `Write` — parsed cleanly → upsert with the stringified value
 *   - `Error` — failed to parse → collect the reason
 *
 * Modals MUST validate every field before performing any write so a
 * single bad field doesn't leave the config half-applied.
 */
object SetConfigFieldValidator {

    sealed interface FieldSpec {
        val label: String

        /** Whole-number integer constrained to an inclusive range. */
        data class IntRange(override val label: String, val range: kotlin.ranges.IntRange) : FieldSpec

        /** Whole-number long with a minimum (≥ [min]); no upper bound. */
        data class LongMin(override val label: String, val min: Long) : FieldSpec

        /** Decimal number constrained to an inclusive range, NaN/∞ rejected. */
        data class DoubleRange(override val label: String, val range: ClosedFloatingPointRange<Double>) : FieldSpec

        /**
         * Boolean accepting case-insensitive `true|false|yes|no|1|0|on|off`.
         * Always stored as `"true"` / `"false"` for downstream readers.
         */
        data class BoolStrict(override val label: String) : FieldSpec

        /** Case-insensitive enum match; stored uppercase. */
        data class EnumChoice(override val label: String, val allowed: Set<String>) : FieldSpec

        /**
         * Discord channel reference (raw id or `<#id>` mention) resolved
         * against the guild; the channel's **name** is stored. Used by
         * `MOVE`, which `MoveCommand` looks up by name — switching to id
         * would silently break unscoped admins.
         */
        data class ChannelByIdStoreName(override val label: String) : FieldSpec

        /**
         * Discord channel reference resolved against the guild; the
         * channel's **id** is stored as a string. Used for
         * LEADERBOARD_CHANNEL, LOTTERY_CHANNEL, CASINO_MODLOG_CHANNEL_ID.
         * `"0"` clears the override to empty string when [allowClear].
         */
        data class ChannelByIdStoreId(override val label: String, val allowClear: Boolean = true) : FieldSpec
    }

    sealed interface FieldResult {
        data object Skip : FieldResult
        data class Write(val value: String) : FieldResult
        data class Error(val message: String) : FieldResult
    }

    /** Run a single field through its spec. Public for direct unit testing. */
    fun validate(input: String?, spec: FieldSpec, guild: Guild? = null): FieldResult {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return FieldResult.Skip

        return when (spec) {
            is FieldSpec.IntRange -> parseInt(raw, spec)
            is FieldSpec.LongMin -> parseLong(raw, spec)
            is FieldSpec.DoubleRange -> parseDouble(raw, spec)
            is FieldSpec.BoolStrict -> parseBool(raw, spec)
            is FieldSpec.EnumChoice -> parseEnum(raw, spec)
            is FieldSpec.ChannelByIdStoreName -> resolveChannelName(raw, spec, guild)
            is FieldSpec.ChannelByIdStoreId -> resolveChannelId(raw, spec, guild)
        }
    }

    /**
     * Run [validate] over every (key, spec) pair in insertion order.
     * `readField(key)` returns the raw text the modal submitted for
     * that field; pass `null` for fields the modal doesn't expose.
     *
     * Output preserves spec insertion order so error messages appear
     * top-to-bottom matching the visual field order in the modal.
     */
    fun validateAll(
        readField: (ConfigDto.Configurations) -> String?,
        specs: Map<ConfigDto.Configurations, FieldSpec>,
        guild: Guild? = null,
    ): ValidationOutcome {
        val writes = mutableListOf<Pair<ConfigDto.Configurations, String>>()
        val errors = mutableListOf<String>()
        for ((key, spec) in specs) {
            when (val result = validate(readField(key), spec, guild)) {
                FieldResult.Skip -> Unit
                is FieldResult.Write -> writes += key to result.value
                is FieldResult.Error -> errors += result.message
            }
        }
        return if (errors.isNotEmpty()) ValidationOutcome.Errors(errors)
        else ValidationOutcome.Writes(writes)
    }

    sealed interface ValidationOutcome {
        data class Writes(val pairs: List<Pair<ConfigDto.Configurations, String>>) : ValidationOutcome
        data class Errors(val messages: List<String>) : ValidationOutcome
    }

    // ------------------------------------------------------------------
    // Per-type parsers (private — exposed only via `validate`)
    // ------------------------------------------------------------------

    private fun parseInt(raw: String, spec: FieldSpec.IntRange): FieldResult {
        val parsed = raw.toIntOrNull()
            ?: return FieldResult.Error("${spec.label}: must be a whole number")
        return if (parsed !in spec.range) FieldResult.Error(
            "${spec.label}: must be between ${spec.range.first} and ${spec.range.last}"
        ) else FieldResult.Write(parsed.toString())
    }

    private fun parseLong(raw: String, spec: FieldSpec.LongMin): FieldResult {
        val parsed = raw.toLongOrNull()
            ?: return FieldResult.Error("${spec.label}: must be a whole number")
        return if (parsed < spec.min) FieldResult.Error(
            "${spec.label}: must be at least ${spec.min}"
        ) else FieldResult.Write(parsed.toString())
    }

    private fun parseDouble(raw: String, spec: FieldSpec.DoubleRange): FieldResult {
        val parsed = raw.toDoubleOrNull()
            ?: return FieldResult.Error("${spec.label}: must be a number")
        if (parsed.isNaN() || parsed.isInfinite())
            return FieldResult.Error("${spec.label}: must be a finite number")
        return if (parsed < spec.range.start || parsed > spec.range.endInclusive)
            FieldResult.Error("${spec.label}: must be between ${spec.range.start} and ${spec.range.endInclusive}")
        else FieldResult.Write(parsed.toString())
    }

    private fun parseBool(raw: String, spec: FieldSpec.BoolStrict): FieldResult {
        return when (raw.lowercase()) {
            "true", "yes", "1", "on", "enabled" -> FieldResult.Write("true")
            "false", "no", "0", "off", "disabled" -> FieldResult.Write("false")
            else -> FieldResult.Error("${spec.label}: must be true/false")
        }
    }

    private fun parseEnum(raw: String, spec: FieldSpec.EnumChoice): FieldResult {
        val upper = raw.uppercase()
        return if (upper in spec.allowed) FieldResult.Write(upper)
        else FieldResult.Error("${spec.label}: must be one of ${spec.allowed.joinToString()}")
    }

    private fun resolveChannelName(raw: String, spec: FieldSpec.ChannelByIdStoreName, guild: Guild?): FieldResult {
        if (guild == null) return FieldResult.Error("${spec.label}: no guild context to resolve channel")
        val id = extractChannelId(raw)
            ?: return FieldResult.Error("${spec.label}: must be a channel id or #mention")
        val ch = guild.getTextChannelById(id)
            ?: guild.getVoiceChannelById(id)
            ?: return FieldResult.Error("${spec.label}: no channel with id $id in this server")
        return FieldResult.Write(ch.name)
    }

    private fun resolveChannelId(raw: String, spec: FieldSpec.ChannelByIdStoreId, guild: Guild?): FieldResult {
        if (spec.allowClear && raw == "0") return FieldResult.Write("")
        if (guild == null) return FieldResult.Error("${spec.label}: no guild context to resolve channel")
        val id = extractChannelId(raw)
            ?: return FieldResult.Error("${spec.label}: must be a channel id or #mention")
        guild.getTextChannelById(id)
            ?: return FieldResult.Error("${spec.label}: no text channel with id $id in this server")
        return FieldResult.Write(id.toString())
    }

    private fun extractChannelId(raw: String): Long? =
        raw.removePrefix("<#").removeSuffix(">").toLongOrNull()
}
