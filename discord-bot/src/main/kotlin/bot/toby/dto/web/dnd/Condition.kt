package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Condition(
    val index: String?,
    val name: String?,
    val desc: List<String>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                desc.isNullOrEmpty()
                && url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        if (name != null) {
            embedBuilder.setTitle(name)
        }
        if (!desc.isNullOrEmpty()) {
            embedBuilder.setDescription((desc.transformListToString()))
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }

}