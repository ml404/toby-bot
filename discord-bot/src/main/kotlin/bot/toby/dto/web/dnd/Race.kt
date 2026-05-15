package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Race(
    val index: String?,
    val name: String?,
    val speed: Int?,
    val abilityBonuses: List<AbilityBonus>?,
    val alignment: String?,
    val age: String?,
    val size: String?,
    val sizeDescription: String?,
    val startingProficiencies: List<ApiInfo>?,
    val languages: List<ApiInfo>?,
    val languageDesc: String?,
    val traits: List<ApiInfo>?,
    val subraces: List<ApiInfo>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                speed == null &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        sizeDescription?.let { embedBuilder.setDescription(it) }
        speed?.let { embedBuilder.addField("Speed", "$it ft", true) }
        size?.let { embedBuilder.addField("Size", it, true) }
        alignment?.let { embedBuilder.addField("Alignment", truncate(it, 1024), false) }
        age?.let { embedBuilder.addField("Age", truncate(it, 1024), false) }
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
                true
            )
        }
        languageDesc?.let { embedBuilder.addField("Language Notes", truncate(it, 1024), false) }
        if (!traits.isNullOrEmpty()) {
            embedBuilder.addField(
                "Traits",
                traits.joinToString(", ") { it.name },
                false
            )
        }
        if (!subraces.isNullOrEmpty()) {
            embedBuilder.addField(
                "Subraces",
                subraces.joinToString(", ") { it.name },
                false
            )
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }

    private fun truncate(text: String, limit: Int = 1024): String =
        if (text.length <= limit) text else text.substring(0, limit - 1) + "…"
}

data class AbilityBonus(val abilityScore: ApiInfo?, val bonus: Int?)
