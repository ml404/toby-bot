package bot.toby.dto.web.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class Monster(
    val index: String?,
    val name: String?,
    val size: String?,
    val type: String?,
    val subtype: String?,
    val alignment: String?,
    val armorClass: List<MonsterArmorClass>?,
    val hitPoints: Int?,
    val hitDice: String?,
    val hitPointsRoll: String?,
    val speed: MonsterSpeed?,
    val strength: Int?,
    val dexterity: Int?,
    val constitution: Int?,
    val intelligence: Int?,
    val wisdom: Int?,
    val charisma: Int?,
    val proficiencies: List<MonsterProficiency>?,
    val damageVulnerabilities: List<String>?,
    val damageResistances: List<String>?,
    val damageImmunities: List<String>?,
    val conditionImmunities: List<ApiInfo>?,
    val senses: Map<String, Any>?,
    val languages: String?,
    val challengeRating: Double?,
    val xp: Int?,
    val specialAbilities: List<MonsterAction>?,
    val actions: List<MonsterAction>?,
    val legendaryActions: List<MonsterAction>?,
    val url: String?
) : DnDResponse {
    override fun isValidReturnObject(): Boolean =
        !(index.isNullOrEmpty() &&
                name.isNullOrEmpty() &&
                size.isNullOrEmpty() &&
                type.isNullOrEmpty() &&
                hitPoints == null &&
                url.isNullOrEmpty())

    override fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        name?.let { embedBuilder.setTitle(it) }

        val header = listOfNotNull(
            size,
            type?.let { if (subtype.isNullOrBlank()) it else "$it ($subtype)" },
            alignment
        ).joinToString(", ")
        if (header.isNotBlank()) embedBuilder.setDescription("*$header*")

        armorClass?.firstOrNull()?.let {
            embedBuilder.addField("AC", "${it.value}${it.type?.let { t -> " ($t)" } ?: ""}", true)
        }
        hitPoints?.let {
            val hpText = if (hitDice != null) "$it ($hitDice)" else "$it"
            embedBuilder.addField("HP", hpText, true)
        }
        speed?.let {
            val parts = listOfNotNull(
                it.walk?.let { v -> "walk $v" },
                it.fly?.let { v -> "fly $v" },
                it.swim?.let { v -> "swim $v" },
                it.climb?.let { v -> "climb $v" },
                it.burrow?.let { v -> "burrow $v" }
            )
            if (parts.isNotEmpty()) embedBuilder.addField("Speed", parts.joinToString(", "), true)
        }

        val abilityScores = listOfNotNull(
            strength?.let { "STR ${it} (${formatMod(it)})" },
            dexterity?.let { "DEX ${it} (${formatMod(it)})" },
            constitution?.let { "CON ${it} (${formatMod(it)})" },
            intelligence?.let { "INT ${it} (${formatMod(it)})" },
            wisdom?.let { "WIS ${it} (${formatMod(it)})" },
            charisma?.let { "CHA ${it} (${formatMod(it)})" }
        )
        if (abilityScores.isNotEmpty()) {
            embedBuilder.addField("Ability Scores", abilityScores.joinToString("\n"), true)
        }

        challengeRating?.let {
            val crStr = if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString()
            val xpPart = xp?.let { x -> " ($x XP)" } ?: ""
            embedBuilder.addField("Challenge", "$crStr$xpPart", true)
        }
        if (!damageVulnerabilities.isNullOrEmpty()) {
            embedBuilder.addField("Vulnerabilities", damageVulnerabilities.joinToString(", "), false)
        }
        if (!damageResistances.isNullOrEmpty()) {
            embedBuilder.addField("Resistances", damageResistances.joinToString(", "), false)
        }
        if (!damageImmunities.isNullOrEmpty()) {
            embedBuilder.addField("Damage Immunities", damageImmunities.joinToString(", "), false)
        }
        if (!conditionImmunities.isNullOrEmpty()) {
            embedBuilder.addField(
                "Condition Immunities",
                conditionImmunities.joinToString(", ") { it.name },
                false
            )
        }
        senses?.let { renderSenses(it) }?.let { embedBuilder.addField("Senses", it, false) }
        if (!languages.isNullOrEmpty()) {
            embedBuilder.addField("Languages", languages, false)
        }
        if (!proficiencies.isNullOrEmpty()) {
            val text = proficiencies.joinToString(", ") {
                "${it.proficiency?.name ?: "?"} +${it.value ?: 0}"
            }
            embedBuilder.addField("Proficiencies", truncate(text), false)
        }
        if (!specialAbilities.isNullOrEmpty()) {
            embedBuilder.addField("Special Abilities", renderActions(specialAbilities), false)
        }
        if (!actions.isNullOrEmpty()) {
            embedBuilder.addField("Actions", renderActions(actions), false)
        }
        if (!legendaryActions.isNullOrEmpty()) {
            embedBuilder.addField("Legendary Actions", renderActions(legendaryActions), false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder.build()
    }

    private fun formatMod(score: Int): String {
        // D&D ability modifier is floor((score - 10) / 2). Kotlin/Java integer
        // division truncates toward zero, so use Math.floorDiv for correct negatives.
        val mod = Math.floorDiv(score - 10, 2)
        return if (mod >= 0) "+$mod" else "$mod"
    }

    private fun renderSenses(senses: Map<String, Any>): String? {
        if (senses.isEmpty()) return null
        return senses.entries.joinToString(", ") { (k, v) ->
            val pretty = when (v) {
                is Double -> if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()
                else -> v.toString()
            }
            "${k.replace('_', ' ')} $pretty"
        }
    }

    private fun renderActions(list: List<MonsterAction>): String {
        val text = list.joinToString("\n\n") {
            val name = it.name ?: "?"
            val desc = it.desc?.replace(Regex("\\s+"), " ")?.trim() ?: ""
            "**$name**: $desc"
        }
        return truncate(text)
    }

    private fun truncate(text: String, limit: Int = 1024): String =
        if (text.length <= limit) text else text.substring(0, limit - 1) + "…"
}

data class MonsterArmorClass(val type: String?, val value: Int)

data class MonsterSpeed(
    val walk: String?,
    val fly: String?,
    val swim: String?,
    val climb: String?,
    val burrow: String?
)

data class MonsterProficiency(val value: Int?, val proficiency: ApiInfo?)

data class MonsterAction(val name: String?, val desc: String?)
