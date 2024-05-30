package toby.helpers

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.buttons.Button
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.service.IUserService
import java.awt.Color
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
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
            val userDto = UserDtoHelper.calculateUserDto(target.guild.idLong, target.idLong, target.isOwner, userService)
            rollAndAddToMap(initiativeMap, target.user.effectiveName, userDto.initiativeModifier)
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
            hook.setEphemeral(true).sendMessage("Next turn: ${sortedEntries[initiativeIndex.get()].key}").queue(invokeDeleteOnMessageResponse(deleteDelay ?: 0)
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

    data class TableButtons(val prev: Button, val clear: Button, val next: Button)
}
