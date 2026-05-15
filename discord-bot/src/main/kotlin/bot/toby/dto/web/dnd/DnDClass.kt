package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class DnDClass(
    val index: String?,
    val name: String?,
    val hitDie: Int?,
    val proficiencyChoices: List<ChoiceBlock>?,
    val proficiencies: List<ApiInfo>?,
    val savingThrows: List<ApiInfo>?,
    val startingEquipment: List<StartingEquipmentEntry>?,
    val classLevels: String?,
    val multiClassing: MultiClassing?,
    val subclasses: List<ApiInfo>?,
    val spellcasting: ClassSpellcasting?,
    val spells: String?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                hitDie == null &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        hitDie?.let { embedBuilder.addField("Hit Die", "d$it", true) }
        spellcasting?.spellcastingAbility?.let {
            embedBuilder.addField("Spellcasting", it.name, true)
        }
        if (!savingThrows.isNullOrEmpty()) {
            embedBuilder.addField(
                "Saving Throws",
                savingThrows.joinToString(", ") { it.name },
                false
            )
        }
        if (!proficiencies.isNullOrEmpty()) {
            val text = proficiencies.joinToString(", ") { it.name }
            embedBuilder.addField("Proficiencies", truncate(text), false)
        }
        if (!startingEquipment.isNullOrEmpty()) {
            val text = startingEquipment.joinToString("\n") {
                "• ${it.equipment?.name ?: "?"} x${it.quantity ?: 1}"
            }
            embedBuilder.addField("Starting Equipment", truncate(text), false)
        }
        if (!subclasses.isNullOrEmpty()) {
            embedBuilder.addField(
                "Subclasses",
                subclasses.joinToString(", ") { it.name },
                false
            )
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }

    private fun truncate(text: String, limit: Int = 1024): String =
        if (text.length <= limit) text else text.substring(0, limit - 1) + "…"
}

data class ChoiceBlock(val choose: Int?, val type: String?)

data class StartingEquipmentEntry(val equipment: ApiInfo?, val quantity: Int?)

data class MultiClassing(val prerequisites: List<MultiClassPrereq>?, val proficiencies: List<ApiInfo>?)

data class MultiClassPrereq(val abilityScore: ApiInfo?, val minimumScore: Int?)

data class ClassSpellcasting(val level: Int?, val spellcastingAbility: ApiInfo?)
