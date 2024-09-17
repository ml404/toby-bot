package toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Rule(
    val index: String?,
    val name: String?,
    val desc: String?,
    val url: String?
): DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                desc.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        if (name != null) {
            embedBuilder.setTitle(name)
        }
        if (!desc.isNullOrEmpty()) {
            embedBuilder.setDescription(desc)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }

}