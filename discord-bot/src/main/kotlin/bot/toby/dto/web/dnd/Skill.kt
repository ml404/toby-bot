package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Skill(
    val index: String?,
    val name: String?,
    val desc: List<String>?,
    val abilityScore: ApiInfo?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                desc.isNullOrEmpty() &&
                abilityScore == null &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        if (!desc.isNullOrEmpty()) {
            embedBuilder.setDescription(desc.transformListToString())
        }
        abilityScore?.let { embedBuilder.addField("Ability Score", it.name, true) }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}
