package bot.toby.dto.web.dnd

import com.google.gson.annotations.SerializedName
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

data class CharacterSheet(
    val id: Long?,
    val name: String?,
    val stats: List<AbilityStat>?,
    val baseHitPoints: Int?,
    val bonusHitPoints: Int?,
    val removedHitPoints: Int?,
    val temporaryHitPoints: Int?,
    val race: CharacterRace?,
    val classes: List<CharacterClass>?,
    // Bonus / override ability scores
    val bonusStats: List<AbilityStat>? = null,
    val overrideStats: List<AbilityStat>? = null,
    val overrideHitPoints: Int? = null,
    // Progression
    val currentXp: Int? = null,
    val inspiration: Boolean? = null,
    val alignmentId: Int? = null,
    val lifestyleId: Int? = null,
    // Character details
    val background: CharacterBackground? = null,
    val traits: CharacterTraits? = null,
    val notes: CharacterNotes? = null,
    val currency: CharacterCurrency? = null,
    // Game mechanics data (available for future use)
    val modifiers: CharacterModifiers? = null,
    val inventory: List<InventoryItem>? = null,
    val spells: CharacterSpellList? = null,
    val actions: CharacterActionList? = null,
    // Appearance
    val gender: String? = null,
    val age: Int? = null,
    val height: String? = null,
    val weight: Int? = null,
    val hair: String? = null,
    val eyes: String? = null,
    val skin: String? = null,
    val faith: String? = null,
    val socialName: String? = null
) {
    companion object {
        const val STR = 1
        const val DEX = 2
        const val CON = 3
        const val INT = 4
        const val WIS = 5
        const val CHA = 6
    }

    /** Base stat (unchanged — used for initiative sync and modifier calculations) */
    fun getStat(id: Int): Int = stats?.firstOrNull { it.id == id }?.value ?: 10

    /** Effective stat accounting for bonuses and overrides */
    fun getEffectiveStat(id: Int): Int {
        overrideStats?.firstOrNull { it.id == id }?.value?.let { return it }
        val base = stats?.firstOrNull { it.id == id }?.value ?: 10
        val bonus = bonusStats?.firstOrNull { it.id == id }?.value ?: 0
        return base + bonus
    }

    fun modifier(id: Int): Int = Math.floorDiv(getStat(id) - 10, 2)

    fun currentHp(): Int = (baseHitPoints ?: 0) + (bonusHitPoints ?: 0) - (removedHitPoints ?: 0)

    fun maxHp(): Int = (baseHitPoints ?: 0) + (bonusHitPoints ?: 0)

    fun raceName(): String = race?.fullName ?: race?.baseName ?: "Unknown"

    fun classesString(): String = classes?.joinToString(", ") {
        val sub = it.subclassDefinition?.name
        val base = it.definition?.name ?: "?"
        val className = if (sub != null) "$base ($sub)" else base
        "$className ${it.level ?: "?"}"
    } ?: "Unknown"

    fun totalLevel(): Int = classes?.sumOf { it.level ?: 0 } ?: 0

    fun proficiencyBonus(): Int = ((totalLevel() - 1) / 4) + 2

    fun passivePerception(): Int = 10 + modifier(WIS)

    fun walkSpeed(): Int = race?.weightSpeeds?.normal ?: 30

    fun backgroundName(): String = background?.definition?.name ?: "Unknown"

    fun alignmentName(): String = when (alignmentId) {
        1 -> "Lawful Good"
        2 -> "Neutral Good"
        3 -> "Chaotic Good"
        4 -> "Lawful Neutral"
        5 -> "True Neutral"
        6 -> "Chaotic Neutral"
        7 -> "Lawful Evil"
        8 -> "Neutral Evil"
        9 -> "Chaotic Evil"
        else -> "Unknown"
    }

    fun currencySummary(): String {
        val cur = currency ?: return "—"
        return listOfNotNull(
            cur.pp?.takeIf { it > 0 }?.let { "${it}pp" },
            cur.gp?.takeIf { it > 0 }?.let { "${it}gp" },
            cur.ep?.takeIf { it > 0 }?.let { "${it}ep" },
            cur.sp?.takeIf { it > 0 }?.let { "${it}sp" },
            cur.cp?.takeIf { it > 0 }?.let { "${it}cp" }
        ).joinToString(" ").ifEmpty { "—" }
    }

    private fun modifierString(id: Int): String {
        val mod = modifier(id)
        return if (mod >= 0) "+$mod" else "$mod"
    }

    fun toEmbed(): MessageEmbed {
        val embedBuilder = EmbedBuilder()
        embedBuilder.setTitle(name ?: "Unknown Character")
        embedBuilder.setColor(0x42f5a7)

        // Row 1: Identity
        embedBuilder.addField("Race", raceName(), true)
        embedBuilder.addField("Class", classesString(), true)
        embedBuilder.addField("Level", totalLevel().toString(), true)

        // Row 2: Background & alignment
        embedBuilder.addField("Alignment", alignmentName(), true)
        embedBuilder.addField("Background", backgroundName(), true)
        embedBuilder.addField("XP", (currentXp ?: 0).toString(), true)

        // Row 3: Combat stats
        embedBuilder.addField("Prof. Bonus", "+${proficiencyBonus()}", true)
        embedBuilder.addField("Speed", "${walkSpeed()} ft", true)
        embedBuilder.addField("Inspiration", if (inspiration == true) "✓" else "—", true)

        // Row 4: STR / DEX / CON
        embedBuilder.addField("STR", "${getStat(STR)} (${modifierString(STR)})", true)
        embedBuilder.addField("DEX", "${getStat(DEX)} (${modifierString(DEX)})", true)
        embedBuilder.addField("CON", "${getStat(CON)} (${modifierString(CON)})", true)

        // Row 5: INT / WIS / CHA
        embedBuilder.addField("INT", "${getStat(INT)} (${modifierString(INT)})", true)
        embedBuilder.addField("WIS", "${getStat(WIS)} (${modifierString(WIS)})", true)
        embedBuilder.addField("CHA", "${getStat(CHA)} (${modifierString(CHA)})", true)

        // Row 6: HP & Perception
        embedBuilder.addField("HP", "${currentHp()}/${maxHp()}", true)
        embedBuilder.addField("Passive Perception", passivePerception().toString(), true)
        if ((temporaryHitPoints ?: 0) > 0) {
            embedBuilder.addField("Temp HP", temporaryHitPoints.toString(), true)
        }

        // Row 7: Currency (full width)
        embedBuilder.addField("Currency", currencySummary(), false)

        return embedBuilder.build()
    }
}

data class AbilityStat(val id: Int, val value: Int?)

data class CharacterRace(
    val fullName: String?,
    val baseName: String?,
    val weightSpeeds: WeightSpeeds? = null,
    val size: String? = null
)

data class CharacterClass(
    val level: Int?,
    val definition: ClassDefinition?,
    val subclassDefinition: ClassDefinition? = null,
    val hitDiceUsed: Int? = null
)

data class ClassDefinition(val name: String?)

// --- Traits, notes, background ---

data class CharacterTraits(
    val personalityTraits: String? = null,
    val ideals: String? = null,
    val bonds: String? = null,
    val flaws: String? = null,
    val appearance: String? = null
)

data class CharacterNotes(
    val allies: String? = null,
    val personalPossessions: String? = null,
    val otherHoldings: String? = null,
    val organizations: String? = null,
    val enemies: String? = null,
    val backstory: String? = null,
    val otherNotes: String? = null
)

data class CharacterCurrency(
    val cp: Int? = null,
    val sp: Int? = null,
    val ep: Int? = null,
    val gp: Int? = null,
    val pp: Int? = null
)

data class BackgroundDefinition(
    val name: String? = null,
    val description: String? = null,
    val featureName: String? = null,
    val featureDescription: String? = null
)

data class CharacterBackground(
    val hasCustomBackground: Boolean? = null,
    val definition: BackgroundDefinition? = null
)

// --- Race ---

data class WeightSpeeds(
    val normal: Int? = null,
    val fly: Int? = null,
    val burrow: Int? = null,
    val swim: Int? = null,
    val climb: Int? = null
)

// --- Modifiers ---

data class CharacterModifier(
    val type: String? = null,
    val subType: String? = null,
    val statId: Int? = null,
    val value: Int? = null,
    val fixedValue: Int? = null,
    val friendlyTypeName: String? = null,
    val friendlySubtypeName: String? = null
)

data class CharacterModifiers(
    val race: List<CharacterModifier>? = null,
    @SerializedName("class") val classModifiers: List<CharacterModifier>? = null,
    val background: List<CharacterModifier>? = null,
    val feat: List<CharacterModifier>? = null,
    val item: List<CharacterModifier>? = null,
    val condition: List<CharacterModifier>? = null
)

// --- Inventory ---

data class DamageInfo(
    val diceString: String? = null,
    val diceCount: Int? = null,
    val diceValue: Int? = null,
    val fixedValue: Int? = null
)

data class InventoryItemDefinition(
    val name: String? = null,
    val type: String? = null,
    val filterType: String? = null,
    val weight: Double? = null,
    val armorClass: Int? = null,
    val armorTypeId: Int? = null,
    val damage: DamageInfo? = null,
    val damageType: String? = null,
    val range: Int? = null,
    val longRange: Int? = null,
    val rarity: String? = null,
    val magic: Boolean? = null
)

data class InventoryItem(
    val id: Long? = null,
    val definition: InventoryItemDefinition? = null,
    val quantity: Int? = null,
    val isAttuned: Boolean? = null,
    val equipped: Boolean? = null
)

// --- Spells ---

data class SpellDefinition(
    val id: Long? = null,
    val name: String? = null,
    val level: Int? = null,
    val school: String? = null,
    val castingTime: String? = null,
    val range: String? = null,
    val duration: String? = null,
    val concentration: Boolean? = null,
    val ritual: Boolean? = null
)

data class CharacterSpellEntry(
    val id: Long? = null,
    val entityTypeId: Long? = null,
    val prepared: Boolean? = null,
    val definition: SpellDefinition? = null
)

data class CharacterSpellList(
    @SerializedName("class") val classSpells: List<CharacterSpellEntry>? = null,
    val race: List<CharacterSpellEntry>? = null,
    val background: List<CharacterSpellEntry>? = null,
    val feat: List<CharacterSpellEntry>? = null,
    val item: List<CharacterSpellEntry>? = null
)

// --- Actions ---

data class LimitedUse(
    val maxUses: Int? = null,
    val numberUsed: Int? = null,
    val resetType: String? = null
)

data class ActionActivation(
    val activationTime: Int? = null,
    val activationType: Int? = null
)

data class CharacterAction(
    val id: Long? = null,
    val name: String? = null,
    val activation: ActionActivation? = null,
    val limitedUse: LimitedUse? = null
)

data class CharacterActionList(
    val race: List<CharacterAction>? = null,
    @SerializedName("class") val classActions: List<CharacterAction>? = null,
    val feat: List<CharacterAction>? = null
)
