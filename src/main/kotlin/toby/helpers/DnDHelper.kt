package toby.helpers

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.buttons.Button
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.command.commands.dnd.DnDCommand.Companion.CONDITION_NAME
import toby.command.commands.dnd.DnDCommand.Companion.FEATURE_NAME
import toby.command.commands.dnd.DnDCommand.Companion.RULE_NAME
import toby.command.commands.dnd.DnDCommand.Companion.SPELL_NAME
import toby.dto.web.dnd.*
import toby.jpa.service.IUserService
import java.awt.Color
import java.lang.String.join
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.random.Random

object DnDHelper {
    val initiativeIndex = AtomicInteger(0)
    var sortedEntries = LinkedList<Map.Entry<String, Int>>()

    fun rollInitiativeForMembers(
        memberList: List<Member>,
        dm: Member,
        initiativeMap: MutableMap<String, Int>,
        userService: IUserService
    ) {
        val nonDmMembers = memberList.filter { it != dm && !it.user.isBot }
        nonDmMembers.forEach { target ->
            val userDto =
                UserDtoHelper.calculateUserDto(target.guild.idLong, target.idLong, target.isOwner, userService)
            rollAndAddToMap(initiativeMap, target.user.effectiveName, userDto.initiativeModifier ?: 0)
        }
        sortMap(initiativeMap)
    }

    fun rollInitiativeForString(nameList: List<String>, initiativeMap: MutableMap<String, Int>) {
        nameList.forEach { name -> rollAndAddToMap(initiativeMap, name, 0) }
        sortMap(initiativeMap)
    }

    private fun sortMap(initiativeMap: Map<String, Int>) {
        sortedEntries = LinkedList(initiativeMap.entries.sortedByDescending { it.value })
    }

    private fun rollAndAddToMap(initiativeMap: MutableMap<String, Int>, name: String, modifier: Int) {
        val diceRoll = rollDiceWithModifier(20, 1, modifier)
        initiativeMap[name] = diceRoll
    }

    fun rollDiceWithModifier(diceValue: Int, diceToRoll: Int, modifier: Int): Int {
        return rollDice(diceValue, diceToRoll) + modifier
    }

    fun rollDice(diceValue: Int, diceToRoll: Int): Int {
        return (0 until diceToRoll).sumOf { Random.nextInt(1, diceValue + 1) }
    }

    fun incrementTurnTable(hook: InteractionHook, event: ButtonInteractionEvent?, deleteDelay: Int?) {
        incrementIndex()
        val embedBuilder = initiativeEmbedBuilder
        sendOrEditInitiativeMessage(hook, embedBuilder, event, deleteDelay)
    }

    private fun incrementIndex() {
        initiativeIndex.incrementAndGet()
        if (initiativeIndex.get() >= sortedEntries.size) {
            initiativeIndex.set(0)
        }
    }

    fun decrementTurnTable(hook: InteractionHook, event: ButtonInteractionEvent?, deleteDelay: Int?) {
        decrementIndex()
        val embedBuilder = initiativeEmbedBuilder
        sendOrEditInitiativeMessage(hook, embedBuilder, event, deleteDelay)
    }

    private fun decrementIndex() {
        initiativeIndex.decrementAndGet()
        if (initiativeIndex.get() < 0) {
            initiativeIndex.set(sortedEntries.size - 1)
        }
    }

    val initButtons = TableButtons(
        Button.primary("init:prev", "⬅️"),
        Button.primary("init:clear", "❌"),
        Button.primary("init:next", "➡️")
    )

    fun sendOrEditInitiativeMessage(
        hook: InteractionHook,
        embedBuilder: EmbedBuilder,
        event: ButtonInteractionEvent?,
        deleteDelay: Int?
    ) {
        val initButtons = initButtons
        val messageEmbed = embedBuilder.build()
        if (event == null) {
            hook.sendMessageEmbeds(messageEmbed)
                .setActionRow(initButtons.prev, initButtons.clear, initButtons.next)
                .queue()
        } else {
            // if we're here we came via a button press, so edit the embed rather than make a new one
            event.message
                .editMessageEmbeds(messageEmbed)
                .setActionRow(initButtons.prev, initButtons.clear, initButtons.next)
                .queue()
            hook.setEphemeral(true).sendMessage("Next turn: ${sortedEntries[initiativeIndex.get()].key}").queue(
                invokeDeleteOnMessageResponse(deleteDelay ?: 0)
            )
        }
    }

    val initiativeEmbedBuilder: EmbedBuilder
        get() {
            val embedBuilder = EmbedBuilder()
            embedBuilder.setColor(Color.GREEN)
            embedBuilder.setTitle("Initiative Order")
            val description = sortedEntries.withIndex().joinToString("\n") { (index, entry) ->
                "${index + initiativeIndex.get() % sortedEntries.size}: ${entry.key}: ${entry.value}"
            }
            embedBuilder.setDescription(description)
            return embedBuilder
        }

    fun clearInitiative() {
        initiativeIndex.set(0)
        sortedEntries.clear()
    }

    fun clearInitiative(hook: InteractionHook, event: ButtonInteractionEvent) {
        event.message.delete().queue()
        initiativeIndex.set(0)
        sortedEntries.clear()
        hook.deleteOriginal().queue()
    }

    suspend fun doInitialLookup(
        typeName: String?,
        typeValue: String?,
        query: String,
        httpHelper: HttpHelper
    ): DnDResponse? {
        val url = "https://www.dnd5eapi.co/api/$typeValue/${query.replaceSpaceWithDash()}"
        val responseData = httpHelper.fetchFromGet(url)
        return when (typeName) {
            SPELL_NAME -> JsonParser.parseJSONToSpell(responseData)
            CONDITION_NAME -> JsonParser.parseJsonToCondition(responseData)
            RULE_NAME -> JsonParser.parseJsonToRule(responseData)
            FEATURE_NAME -> JsonParser.parseJsonToFeature(responseData)
            else -> null
        }
    }

    suspend fun queryNonMatchRetry(
        typeValue: String?,
        query: String,
        httpHelper: HttpHelper
    ): QueryResult? {
        val queryUrl = "https://www.dnd5eapi.co/api/$typeValue?name=${query.replaceSpaceWithUrlEncode()}"
        val queryResponseData = httpHelper.fetchFromGet(queryUrl)
        return JsonParser.parseJsonToQueryResult(queryResponseData)
    }

    fun DnDResponse.toEmbed(): MessageEmbed {
        return when (this) {
            is Spell -> createEmbedFromSpell(this).build()
            is Condition -> createEmbedFromInformation(this).build()
            is Rule -> createEmbedFromRule(this).build()
            is Feature -> createEmbedFromFeature(this).build()
            else -> throw Error("Something has gone horribly wrong")
        }
    }

    private fun createEmbedFromSpell(spell: Spell): EmbedBuilder {
        val embedBuilder = EmbedBuilder()
        spell.name?.let { embedBuilder.setTitle(spell.name) }
        if (spell.desc.isNotEmpty()) {
            embedBuilder.setDescription(spell.desc.transformListToString())
        }
        if (spell.higherLevel.isNotEmpty()) {
            embedBuilder.addField("Higher Level", spell.higherLevel.transformListToString(), false)
        }
        if (spell.range != null) {
            val meterValue = if (spell.range == "Touch") "Touch" else buildString {
                append(
                    transformToMeters(
                        spell.range.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[0].toInt()
                    )
                )
                append("m")
            }
            embedBuilder.addField("Range", meterValue, true)
        }
        if (spell.components.isNotEmpty()) {
            embedBuilder.addField("Components", join(", ", spell.components), true)
        }
        spell.duration?.let { embedBuilder.addField("Duration", spell.duration, true) }
        spell.concentration?.let { embedBuilder.addField("Concentration", spell.concentration.toString(), true) }
        spell.castingTime?.let { embedBuilder.addField("Casting Time", spell.castingTime, true) }
        spell.level?.let { embedBuilder.addField("Level", spell.level.toString(), true) }

        if (spell.damage != null) {
            val damageInfo = StringBuilder()
            damageInfo.append("Damage Type: ").append(spell.damage.damageType.name).append("\n")

            // Add damage at slot level information
            val damageAtSlotLevel = spell.damage.damageAtSlotLevel
            if (damageAtSlotLevel != null) {
                damageInfo.append("Damage at Slot Level:\n")
                for ((key, value) in damageAtSlotLevel) {
                    damageInfo.append("Level ").append(key).append(": ").append(value).append("\n")
                }
            }
            embedBuilder.addField("Damage Info", damageInfo.toString(), true)
        }
        val dc = spell.dc
        if (dc != null) {
            embedBuilder.addField("DC Type", dc.dcType.name, true)
            if (dc.dcSuccess != null) {
                embedBuilder.addField("DC Success", dc.dcSuccess, true)
            }
        }
        if (spell.areaOfEffect != null) {
            embedBuilder.addField(
                "Area of Effect",
                "Type: ${spell.areaOfEffect.type}, Size: ${transformToMeters(spell.areaOfEffect.size)}m",
                true
            )
        }
        if (spell.school != null) {
            embedBuilder.addField("School", spell.school.name, true)
        }
        val spellClasses = spell.classes
        if (!spellClasses.isNullOrEmpty()) {
            val classesInfo = StringBuilder()
            for (classInfo in spellClasses) {
                classesInfo.append(classInfo.name).append("\n")
            }
            embedBuilder.addField("Classes", classesInfo.toString(), true)
        }
        val subclasses = spell.subclasses
        if (subclasses.isNotEmpty()) {
            val subclassesInfo = StringBuilder()
            for (subclassInfo in subclasses) {
                subclassesInfo.append(subclassInfo?.name).append("\n")
            }
            embedBuilder.addField("Subclasses", subclassesInfo.toString(), true)
        }
        if (spell.url != null) {
            embedBuilder.setUrl("https://www.dndbeyond.com/" + spell.url.replace("/api/", ""))
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder
    }

    private fun createEmbedFromInformation(information: Condition): EmbedBuilder {
        val embedBuilder = EmbedBuilder()
        if (information.name != null) {
            embedBuilder.setTitle(information.name)
        }
        if (!information.desc.isNullOrEmpty()) {
            embedBuilder.setDescription((information.desc.transformListToString()))
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder
    }

    private fun createEmbedFromRule(rule: Rule): EmbedBuilder {
        val embedBuilder = EmbedBuilder()
        if (rule.name != null) {
            embedBuilder.setTitle(rule.name)
        }
        if (!rule.desc.isNullOrEmpty()) {
            embedBuilder.setDescription(rule.desc)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder
    }

    private fun createEmbedFromFeature(feature: Feature): EmbedBuilder {
        val embedBuilder = EmbedBuilder()
        if (feature.name != null) {
            embedBuilder.setTitle(feature.name)
        }
        if (!feature.desc.isNullOrEmpty()) {
            embedBuilder.setDescription(feature.desc.transformListToString())
        }
        if (feature.classInfo != null) {
            embedBuilder.addField("Class", feature.classInfo.name, true)
        }
        feature.level?.let { embedBuilder.addField("Level", feature.level.toString(), true) }
        if (feature.prerequisites.isNotEmpty()) {
            embedBuilder.addField("Prerequisites", feature.prerequisites.transformListToString(), false)
        }
        embedBuilder.setColor(0x42f5a7)
        return embedBuilder
    }

    private fun List<String?>.transformListToString(): String {
        return this.stream().reduce { s1: String?, s2: String? -> join("\n", s1, s2) }.get()
    }

    private fun transformToMeters(rangeNumber: Int): String {
        return (rangeNumber.toDouble() / 3.28).roundToInt().toString()
    }

    private fun String.replaceSpaceWithDash(): String {
        return this.replace(" ", "-")
    }

    private fun String.replaceSpaceWithUrlEncode(): String {
        return this.replace(" ", "%20")
    }

    data class TableButtons(val prev: Button, val clear: Button, val next: Button)
}