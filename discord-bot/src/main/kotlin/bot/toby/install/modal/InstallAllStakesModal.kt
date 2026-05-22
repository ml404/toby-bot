package bot.toby.install.modal

import bot.toby.modal.modals.setconfig.SetConfigStakesModal
import core.modal.Modal
import core.modal.ModalContext
import database.dto.ConfigDto.Configurations
import database.service.ConfigService
import net.dv8tion.jda.api.components.label.Label
import net.dv8tion.jda.api.components.textinput.TextInput
import net.dv8tion.jda.api.components.textinput.TextInputStyle
import org.springframework.stereotype.Component
import net.dv8tion.jda.api.modals.Modal as JdaModal

/**
 * "Apply to all games" stakes modal — writes a single (min, max) pair to
 * every game's `*_MIN_STAKE` / `*_MAX_STAKE` keys at once, saving the
 * owner from drilling into 10 individual modals when their stakes are
 * uniform. Blank fields are skipped (no write); bot-suspicion edge caps
 * are not touched (those vary per game and stay per-game-tunable via
 * `/setconfig stakes <game>` or the wizard's per-game drill-down).
 */
@Component
class InstallAllStakesModal(
    private val configService: ConfigService,
) : Modal {

    override val name: String = MODAL_NAME

    fun buildModal(currentMin: String?, currentMax: String?): JdaModal {
        val minInput = TextInput.create(FIELD_MIN_STAKE, TextInputStyle.SHORT)
            .setRequired(false)
            .setPlaceholder("≥ 1 (applies to every game)")
            .apply { currentMin?.takeIf { it.isNotEmpty() }?.let { setValue(it) } }
            .build()
        val maxInput = TextInput.create(FIELD_MAX_STAKE, TextInputStyle.SHORT)
            .setRequired(false)
            .setPlaceholder("≥ 1 (applies to every game)")
            .apply { currentMax?.takeIf { it.isNotEmpty() }?.let { setValue(it) } }
            .build()
        return JdaModal.create(MODAL_NAME, "Apply stake bounds to all games")
            .addComponents(
                Label.of("Min stake (credits)", minInput),
                Label.of("Max stake (credits)", maxInput),
            )
            .build()
    }

    override fun handle(ctx: ModalContext, deleteDelay: Int) {
        val event = ctx.event
        val guildId = ctx.guild.id

        val minRaw = event.getValue(FIELD_MIN_STAKE)?.asString?.trim().orEmpty()
        val maxRaw = event.getValue(FIELD_MAX_STAKE)?.asString?.trim().orEmpty()

        val errors = mutableListOf<String>()
        val min = if (minRaw.isEmpty()) null else minRaw.toLongOrNull()?.takeIf { it >= 1L }
            ?: run { errors += "Min stake must be a whole number ≥ 1."; null }
        val max = if (maxRaw.isEmpty()) null else maxRaw.toLongOrNull()?.takeIf { it >= 1L }
            ?: run { errors += "Max stake must be a whole number ≥ 1."; null }
        if (min != null && max != null && min > max) {
            errors += "Min stake ($min) cannot exceed max stake ($max)."
        }

        if (errors.isNotEmpty()) {
            event.reply(
                "Couldn't save — fix these fields:\n" +
                    errors.joinToString("\n") { "• $it" } +
                    "\n\nNo changes were written."
            ).setEphemeral(true).queue()
            return
        }
        if (min == null && max == null) {
            event.reply("No fields filled — nothing changed.").setEphemeral(true).queue()
            return
        }

        val written = mutableListOf<Pair<String, String>>()
        SetConfigStakesModal.Game.entries.forEach { game ->
            if (min != null) {
                configService.upsertConfig(game.minKey.configValue, min.toString(), guildId)
            }
            if (max != null) {
                configService.upsertConfig(game.maxKey.configValue, max.toString(), guildId)
            }
        }
        if (min != null) written += "Min stake" to min.toString()
        if (max != null) written += "Max stake" to max.toString()

        val gameCount = SetConfigStakesModal.Game.entries.size
        val summary = buildString {
            append("Applied to all $gameCount games:\n")
            written.forEach { (label, value) -> append("• $label: $value\n") }
        }
        event.reply(summary).setEphemeral(true).queue()
    }

    @Suppress("unused") // referenced by the menu when reading current values
    fun readCurrentDefaults(currentValues: (Configurations) -> String?): Pair<String?, String?> {
        // Best-effort prefill: show whatever DICE has set (the first enum entry).
        // Owners can overwrite freely; mismatches across games just mean the
        // prefill isn't meaningful and they'll type fresh values anyway.
        val dice = SetConfigStakesModal.Game.DICE
        return currentValues(dice.minKey) to currentValues(dice.maxKey)
    }

    companion object {
        const val MODAL_NAME = "install_all_stakes"
        const val FIELD_MIN_STAKE = "min_stake"
        const val FIELD_MAX_STAKE = "max_stake"
    }
}
