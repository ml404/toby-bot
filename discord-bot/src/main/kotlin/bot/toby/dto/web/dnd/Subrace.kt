package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Subrace(
    val index: String?,
    val name: String?,
    val race: ApiInfo?,
    val desc: String?,
    val abilityBonuses: List<AbilityBonus>?,
    val startingProficiencies: List<ApiInfo>?,
    val languages: List<ApiInfo>?,
    val racialTraits: List<ApiInfo>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                race == null &&
                desc.isNullOrEmpty() &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        desc?.let { embedBuilder.setDescription(it) }
        race?.let { embedBuilder.addField("Parent Race", it.name, true) }
        if (!abilityBonuses.isNullOrEmpty()) {
            val text = abilityBonuses.joinToString("\n") {
                "${it.abilityScore?.name ?: "?"}: +${it.bonus ?: 0}"
            }
            embedBuilder.addField("Ability Bonuses", text, true)
        }
        if (!languages.isNullOrEmpty()) {
            embedBuilder.addField(
                "Languages",
                languages.joinToString(", ") { it.name },
                false
            )
        }
        if (!racialTraits.isNullOrEmpty()) {
            embedBuilder.addField(
                "Racial Traits",
                racialTraits.joinToString(", ") { it.name },
                false
            )
        }
        if (!startingProficiencies.isNullOrEmpty()) {
            embedBuilder.addField(
                "Starting Proficiencies",
                startingProficiencies.joinToString(", ") { it.name },
                false
            )
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}
