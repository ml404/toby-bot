package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Trait(
    val index: String?,
    val name: String?,
    val races: List<ApiInfo>?,
    val subraces: List<ApiInfo>?,
    val desc: List<String>?,
    val proficiencies: List<ApiInfo>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                races.isNullOrEmpty() &&
                subraces.isNullOrEmpty() &&
                desc.isNullOrEmpty() &&
                proficiencies.isNullOrEmpty() &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        if (!desc.isNullOrEmpty()) {
            embedBuilder.setDescription(desc.transformListToString())
        }
        if (!races.isNullOrEmpty()) {
            embedBuilder.addField("Races", races.joinToString(", ") { it.name }, true)
        }
        if (!subraces.isNullOrEmpty()) {
            embedBuilder.addField("Subraces", subraces.joinToString(", ") { it.name }, true)
        }
        if (!proficiencies.isNullOrEmpty()) {
            embedBuilder.addField("Proficiencies", proficiencies.joinToString(", ") { it.name }, false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}
