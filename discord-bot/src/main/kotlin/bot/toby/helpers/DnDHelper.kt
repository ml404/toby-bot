package bot.toby.helpers

import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.CONDITION_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.FEATURE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.RULE_NAME
import bot.toby.command.commands.dnd.DnDSearchCommand.Companion.SPELL_NAME
import bot.toby.dto.web.dnd.DnDResponse
import bot.toby.dto.web.dnd.QueryResult
import common.logging.DiscordLogger
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@Service
class DnDHelper(private val userDtoHelper: UserDtoHelper) {

    private val logger = DiscordLogger(this::class.java)
    private val states: ConcurrentHashMap<Long, InitiativeState> = ConcurrentHashMap()

    val initButtons = TableButtons(
        Button.primary("init:prev", "⬅️"),
        Button.primary("init:clear", "❌"),
        Button.primary("init:next", "➡️")
    )

    /** Returns (creating if absent) the per-guild initiative state. */
    fun stateFor(guildId: Long): InitiativeState =
        states.computeIfAbsent(guildId) { InitiativeState() }

    /** Snapshot of every guild that currently has active initiative state. */
    fun activeSnapshots(): Map<Long, InitiativeStateSnapshot> =
        states.filterValues { it.isActive() }.mapValues { (_, v) -> v.snapshot() }

    /** Replace (or seed) the state for the given guild from a snapshot. */
    fun restore(guildId: Long, snapshot: InitiativeStateSnapshot) {
        stateFor(guildId).restoreFrom(snapshot)
    }

    fun rollInitiativeForMembers(
        guildId: Long,
        memberList: List<Member>,
        dm: Member,
        initiativeMap: MutableMap<String, Int>
    ) {
        val nonDmMembers = memberList.filter { it != dm }.nonBots()
        nonDmMembers.forEach { target ->
            val userDto = userDtoHelper.calculateUserDto(target.idLong, target.guild.idLong, target.isOwner)
            rollAndAddToMap(initiativeMap, target.user.effectiveName, userDto.initiativeModifier ?: 0)
        }
        stateFor(guildId).sortMap(initiativeMap)
    }

    fun rollInitiativeForString(
        guildId: Long,
        nameList: List<String>,
        initiativeMap: MutableMap<String, Int>
    ) {
        nameList.forEach { name -> rollAndAddToMap(initiativeMap, name, 0) }
        stateFor(guildId).sortMap(initiativeMap)
    }

    private fun rollAndAddToMap(initiativeMap: MutableMap<String, Int>, name: String, modifier: Int) {
        initiativeMap[name] = rollDiceWithModifier(20, 1, modifier)
    }

    fun rollDiceWithModifier(diceValue: Int, diceToRoll: Int, modifier: Int): Int =
        rollDice(diceValue, diceToRoll) + modifier

    fun rollDice(diceValue: Int, diceToRoll: Int): Int =
        (0 until diceToRoll).sumOf { Random.nextInt(1, diceValue + 1) }

    fun incrementTurnTable(guildId: Long, hook: InteractionHook, event: ButtonInteractionEvent?, deleteDelay: Int) {
        val state = stateFor(guildId)
        state.incrementIndex()
        sendOrEditInitiativeMessage(guildId, hook, state.initiativeEmbedBuilder, event, deleteDelay)
    }

    fun decrementTurnTable(guildId: Long, hook: InteractionHook, event: ButtonInteractionEvent?, deleteDelay: Int) {
        val state = stateFor(guildId)
        state.decrementIndex()
        sendOrEditInitiativeMessage(guildId, hook, state.initiativeEmbedBuilder, event, deleteDelay)
    }

    fun sendOrEditInitiativeMessage(
        guildId: Long,
        hook: InteractionHook,
        embedBuilder: EmbedBuilder,
        event: ButtonInteractionEvent?,
        deleteDelay: Int
    ) {
        val state = stateFor(guildId)
        val messageEmbed = embedBuilder.build()
        if (event == null) {
            hook.sendMessageEmbeds(messageEmbed)
                .setComponents(ActionRow.of(initButtons.prev, initButtons.clear, initButtons.next))
                .queue()
        } else {
            event.message
                .editMessageEmbeds(messageEmbed)
                .setComponents(ActionRow.of(initButtons.prev, initButtons.clear, initButtons.next))
                .queue()
            hook.setEphemeral(true)
                .sendMessage("Next turn: ${state.sortedEntries[state.initiativeIndex.get()].key}")
                .queue(core.command.Command.invokeDeleteOnMessageResponse(deleteDelay))
        }
    }

    fun clearInitiative(guildId: Long) {
        stateFor(guildId).clear()
    }

    fun clearInitiative(guildId: Long, hook: InteractionHook, event: ButtonInteractionEvent) {
        event.message.delete().queue()
        stateFor(guildId).clear()
        hook.deleteOriginal().queue()
    }

    suspend fun doInitialLookup(
        typeName: String?,
        typeValue: String?,
        query: String,
        httpHelper: HttpHelper
    ): DnDResponse? {
        val url = "https://www.dnd5eapi.co/api/$typeValue/${query.replaceSpaceWithDash()}"
        logger.info("Fetching data from '$url'")
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
        logger.info("Fetching data from '$queryUrl'")
        val queryResponseData = httpHelper.fetchFromGet(queryUrl)
        return JsonParser.parseJsonToQueryResult(queryResponseData)
    }

    private fun String.replaceSpaceWithDash(): String = this.replace(" ", "-")
    private fun String.replaceSpaceWithUrlEncode(): String = this.replace(" ", "%20")

    data class TableButtons(val prev: Button, val clear: Button, val next: Button)
}
