package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Equipment(
    val index: String?,
    val name: String?,
    val equipmentCategory: ApiInfo?,
    val weaponCategory: String?,
    val weaponRange: String?,
    val categoryRange: String?,
    val armorCategory: String?,
    val gearCategory: ApiInfo?,
    val cost: EquipmentCost?,
    val damage: EquipmentDamage?,
    val twoHandedDamage: EquipmentDamage?,
    val range: EquipmentRange?,
    val throwRange: EquipmentRange?,
    val weight: Double?,
    val properties: List<ApiInfo>?,
    val armorClass: ArmorClassInfo?,
    val strMinimum: Int?,
    val stealthDisadvantage: Boolean?,
    val desc: List<String>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                equipmentCategory == null &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }
        if (!desc.isNullOrEmpty()) {
            embedBuilder.setDescription(desc.transformListToString())
        }
        equipmentCategory?.let { embedBuilder.addField("Category", it.name, true) }
        categoryRange?.let { embedBuilder.addField("Range Type", it, true) }
        cost?.let { embedBuilder.addField("Cost", "${it.quantity} ${it.unit}", true) }
        weight?.let { embedBuilder.addField("Weight", "$it lb", true) }
        damage?.let {
            embedBuilder.addField(
                "Damage",
                "${it.damageDice} ${it.damageType?.name ?: ""}".trim(),
                true
            )
        }
        twoHandedDamage?.let {
            embedBuilder.addField(
                "Two-Handed Damage",
                "${it.damageDice} ${it.damageType?.name ?: ""}".trim(),
                true
            )
        }
        range?.let {
            val rangeStr = if (it.long != null) "${it.normal}/${it.long}" else "${it.normal}"
            embedBuilder.addField("Range", rangeStr, true)
        }
        armorCategory?.let { embedBuilder.addField("Armor Category", it, true) }
        armorClass?.let {
            val acStr = buildString {
                append(it.base)
                if (it.dexBonus == true) append(" + Dex")
                it.maxBonus?.let { mb -> append(" (max +$mb)") }
            }
            embedBuilder.addField("Armor Class", acStr, true)
        }
        strMinimum?.let { if (it > 0) embedBuilder.addField("Strength Min", it.toString(), true) }
        stealthDisadvantage?.let { if (it) embedBuilder.addField("Stealth", "Disadvantage", true) }
        if (!properties.isNullOrEmpty()) {
            embedBuilder.addField("Properties", properties.joinToString(", ") { it.name }, false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }
}

data class EquipmentCost(val quantity: Int, val unit: String)

data class EquipmentDamage(val damageDice: String?, val damageType: ApiInfo?)

data class EquipmentRange(val normal: Int?, val long: Int?)

data class ArmorClassInfo(val base: Int, val dexBonus: Boolean?, val maxBonus: Int?)
