package bot.toby.dto.web.dnd

import com.google.gson.annotations.SerializedName
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Subclass(
    val index: String?,
    val name: String?,
    @SerializedName("class")
    val parentClass: ApiInfo?,
    val subclassFlavor: String?,
    val desc: List<String>?,
    val spells: List<SubclassSpell>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                parentClass == null &&
                desc.isNullOrEmpty() &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        if (!desc.isNullOrEmpty()) {
            embedBuilder.setDescription(desc.transformListToString())
        }
        parentClass?.let { embedBuilder.addField("Parent Class", it.name, true) }
        subclassFlavor?.let { embedBuilder.addField("Flavor", it, true) }
        if (!spells.isNullOrEmpty()) {
            val text = spells.joinToString("\n") { "• ${it.spell?.name ?: "?"}" }
            embedBuilder.addField("Spells", truncate(text), false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }

    private fun truncate(text: String, limit: Int = 1024): String =
        if (text.length <= limit) text else text.substring(0, limit - 1) + "…"
}

data class SubclassSpell(val spell: ApiInfo?, val prerequisites: List<ApiInfo>?)
