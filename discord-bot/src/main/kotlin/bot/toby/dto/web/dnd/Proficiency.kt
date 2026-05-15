package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Proficiency(
    val index: String?,
    val name: String?,
    val type: String?,
    val classes: List<ApiInfo>?,
    val races: List<ApiInfo>?,
    val reference: ApiInfo?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                type.isNullOrEmpty() &&
                classes.isNullOrEmpty() &&
                races.isNullOrEmpty() &&
                reference == null &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        type?.let { embedBuilder.addField("Type", it, true) }
        reference?.let { embedBuilder.addField("Reference", it.name, true) }
        if (!classes.isNullOrEmpty()) {
            embedBuilder.addField("Classes", classes.joinToString(", ") { it.name }, false)
        }
        if (!races.isNullOrEmpty()) {
            embedBuilder.addField("Races", races.joinToString(", ") { it.name }, false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}
