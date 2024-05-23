package toby.command.commands.dnd

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.components.selections.SelectOption
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import org.jetbrains.annotations.VisibleForTesting
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.command.commands.fetch.IFetchCommand
import toby.dto.web.dnd.*
import toby.helpers.HttpHelper
import toby.helpers.JsonParser
import toby.jpa.dto.UserDto
import java.lang.String.join
import java.util.function.Consumer

class DnDCommand : IDnDCommand, IFetchCommand {
    override fun handle(ctx: CommandContext, requestingUserDto: UserDto, deleteDelay: Int?) {
        val typeOptionMapping = ctx.event.getOption(TYPE)
        handleWithHttpObjects(
            ctx.event,
            getName(typeOptionMapping),
            typeOptionMapping!!.asString,
            ctx.event.getOption(QUERY)!!.asString,
            HttpHelper(),
            deleteDelay
        )
    }

    @VisibleForTesting
    fun handleWithHttpObjects(
        event: SlashCommandInteractionEvent,
        typeName: String?,
        typeValue: String?,
        query: String,
        httpHelper: HttpHelper,
        deleteDelay: Int?
    ) {
        event.deferReply().queue()
        doLookUpAndReply(event.hook, typeName, typeValue, query, httpHelper, deleteDelay)
    }

    override val name: String
        get() = "dnd"
    override val description: String
        get() = "Use this command to do lookups on various things from DnD"
    override val optionData: List<OptionData>
        get() {
            val type = OptionData(OptionType.STRING, TYPE, "What type are you looking up", true)
            type.addChoice(SPELL_NAME, "spells")
            type.addChoice(CONDITION_NAME, "conditions")
            type.addChoice(RULE_NAME, "rule-sections")
            type.addChoice(FEATURE_NAME, "features")
            val query = OptionData(OptionType.STRING, QUERY, "What is the thing you are looking up?", true)
            return listOf(type, query)
        }

    companion object {
        private const val DND_5_API_URL = "https://www.dnd5eapi.co/api/%s/%s"
        const val TYPE = "type"
        const val QUERY = "query"
        const val SPELL_NAME = "spell"
        const val CONDITION_NAME = "condition"
        const val RULE_NAME = "rule"
        const val FEATURE_NAME = "feature"
        private fun getName(typeOptionMapping: OptionMapping?): String {
            when (typeOptionMapping!!.asString) {
                "spells" -> {
                    return SPELL_NAME
                }

                "conditions" -> {
                    return CONDITION_NAME
                }

                "rule-sections" -> {
                    return RULE_NAME
                }

                "features" -> {
                    return FEATURE_NAME
                }
            }
            return ""
        }

        @JvmStatic
        fun doLookUpAndReply(
            hook: InteractionHook,
            typeName: String?,
            typeValue: String?,
            query: String,
            httpHelper: HttpHelper,
            deleteDelay: Int?
        ) {
            val url = String.format(DND_5_API_URL, typeValue, replaceSpaceWithDash(query))
            val responseData = httpHelper.fetchFromGet(url)
            when (typeName) {
                SPELL_NAME -> {
                    val spell = JsonParser.parseJSONToSpell(responseData)
                    spell?.let {
                        val spellEmbed = createEmbedFromSpell(it)
                        hook.sendMessageEmbeds(spellEmbed.build()).queue()
                    } ?: queryNonMatchRetry(hook, typeName, typeValue, query, httpHelper, deleteDelay)
                }

                CONDITION_NAME -> {
                    val information = JsonParser.parseJsonToInformation(responseData)
                    information?.let {
                        val conditionEmbed = createEmbedFromInformation(information)
                        hook.sendMessageEmbeds(conditionEmbed.build()).queue()
                    } ?: queryNonMatchRetry(hook, typeName, typeValue, query, httpHelper, deleteDelay)
                }

                RULE_NAME -> {
                    val rule = JsonParser.parseJsonToRule(responseData)
                    rule?.let {
                        val conditionEmbed = createEmbedFromRule(rule)
                        hook.sendMessageEmbeds(conditionEmbed.build()).queue()
                    } ?: queryNonMatchRetry(hook, typeName, typeValue, query, httpHelper, deleteDelay)
                }

                FEATURE_NAME -> {
                    val feature = JsonParser.parseJsonToFeature(responseData)
                    feature?.let {
                        val conditionEmbed = createEmbedFromFeature(feature)
                        hook.sendMessageEmbeds(conditionEmbed.build()).queue()
                    } ?: queryNonMatchRetry(hook, typeName, typeValue, query, httpHelper, deleteDelay)
                }

                else -> hook.sendMessage("Something went wrong.")
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
        }

        private fun queryNonMatchRetry(
            hook: InteractionHook,
            typeName: String?,
            typeValue: String?,
            query: String,
            httpHelper: HttpHelper,
            deleteDelay: Int?
        ) {
            val queryResponseData = httpHelper.fetchFromGet(
                String.format(
                    DND_5_API_URL,
                    typeValue,
                    "?name=" + replaceSpaceWithUrlEncode(query)
                )
            )
            val queryResult = JsonParser.parseJsonToQueryResult(queryResponseData)
            if (queryResult != null && queryResult.count > 0) {
                val builder =
                    StringSelectMenu.create(String.format("dnd:%s", typeName)).setPlaceholder("Choose an option")
                queryResult.results.forEach(Consumer { info: ApiInfo ->
                    builder.addOptions(
                        SelectOption.of(
                            info.index,
                            info.index
                        )
                    )
                })
                hook.sendMessageFormat(
                    "Your query '%s' didn't return a value, but these close matches were found, please select one as appropriate",
                    query
                )
                    .addActionRow(builder.build())
                    .queue()
            } else {
                hook.sendMessageFormat("Sorry, nothing was returned for %s '%s'", typeName, query)
                    .queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            }
        }

        private fun createEmbedFromSpell(spell: Spell): EmbedBuilder {
            val embedBuilder = EmbedBuilder()
            spell.name?.let { embedBuilder.setTitle(spell.name) }
            if (spell.desc.isNotEmpty()) {
                embedBuilder.setDescription(transformListToString(spell.desc))
            }
            if (spell.higherLevel.isNotEmpty()) {
                embedBuilder.addField("Higher Level", transformListToString(spell.higherLevel), false)
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
                    "Type: " + spell.areaOfEffect.type + ", Size: " + transformToMeters(spell.areaOfEffect.size) + "m",
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

        private fun createEmbedFromInformation(information: Information): EmbedBuilder {
            val embedBuilder = EmbedBuilder()
            if (information.name != null) {
                embedBuilder.setTitle(information.name)
            }
            if (!information.desc.isNullOrEmpty()) {
                embedBuilder.setDescription(transformListToString(information.desc))
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
                embedBuilder.setDescription(transformListToString(feature.desc))
            }
            if (feature.classInfo != null) {
                embedBuilder.addField("Class", feature.classInfo.name, true)
            }
            feature.level?.let { embedBuilder.addField("Level", feature.level.toString(), true) }
            if (feature.prerequisites.isNotEmpty()) {
                embedBuilder.addField("Prerequisites", transformListToString(feature.prerequisites), false)
            }
            embedBuilder.setColor(0x42f5a7)
            return embedBuilder
        }

        private fun transformListToString(feature: List<String?>): String {
            return feature.stream().reduce { s1: String?, s2: String? -> join("\n", s1, s2) }.get()
        }

        private fun replaceSpaceWithDash(query: String): String {
            return query.replace(" ", "-")
        }

        private fun replaceSpaceWithUrlEncode(query: String): String {
            return query.replace(" ", "%20")
        }

        private fun transformToMeters(rangeNumber: Int): String {
            return Math.round(rangeNumber.toDouble() / 3.28).toString()
        }
    }
}
