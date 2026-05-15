package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class AbilityScore(
    val index: String?,
    val name: String?,
    val fullName: String?,
    val desc: List<String>?,
    val skills: List<ApiInfo>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                fullName.isNullOrEmpty() &&
                desc.isNullOrEmpty() &&
                skills.isNullOrEmpty() &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        val title = fullName ?: name
        title?.let { embedBuilder.setTitle(it) }
        if (!desc.isNullOrEmpty()) {
            embedBuilder.setDescription(desc.transformListToString())
        }
        if (!skills.isNullOrEmpty()) {
            embedBuilder.addField("Skills", skills.joinToString(", ") { it.name }, false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}
