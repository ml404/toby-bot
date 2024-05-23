package toby.helpers

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.buttons.Button
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.service.IUserService
import java.awt.Color
import java.util.*
import java.util.Map.Entry.comparingByValue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

object DnDHelper {
    @JvmField
    val initiativeIndex: AtomicInteger = AtomicInteger(0)

    @JvmStatic
    var sortedEntries: LinkedList<Map.Entry<String, Int>> = LinkedList()
        private set

    fun rollInitiativeForMembers(
        memberList: List<Member>,
        dm: Member,
        initiativeMap: MutableMap<String, Int>,
        userService: IUserService
    ) {
        val nonDmMembers = memberList.stream()
            .filter { memberInChannel: Member -> memberInChannel !== dm && !memberInChannel.user.isBot }
            .toList()
        if (nonDmMembers.isEmpty()) return
        nonDmMembers.forEach { target ->
            val userDto = UserDtoHelper.calculateUserDto(target.guild.idLong, target.idLong, target.isOwner, userService, 20)
            rollAndAddToMap(initiativeMap, target.user.effectiveName, userDto?.initiativeModifier ?: 0)
        }
        sortMap(initiativeMap)
    }

    fun rollInitiativeForString(nameList: List<String>, initiativeMap: MutableMap<String, Int>) {
        if (nameList.isEmpty()) return
        nameList.forEach { name -> rollAndAddToMap(initiativeMap, name, 0) }
        sortMap(initiativeMap)
    }

    private fun sortMap(initiativeMap: Map<String, Int>) {
        sortedEntries = LinkedList(initiativeMap.entries)
        // Sort the list based on values
        sortedEntries.sortWith(comparingByValue(Comparator.reverseOrder()))
    }

    private fun rollAndAddToMap(initiativeMap: MutableMap<String, Int>, name: String, modifier: Int) {
        val diceRoll = rollDiceWithModifier(20, 1, modifier)
        initiativeMap[name] = diceRoll
    }

    private fun createTurnOrderString(): StringBuilder {
        val description = StringBuilder()
        for (i in sortedEntries.indices) {
            val currentIndex = (initiativeIndex.get() + i) % sortedEntries.size
            description.append(sortedEntries[currentIndex].key).append(": ").append(sortedEntries[currentIndex].value)
                .append("\n")
        }
        return description
    }

    @JvmStatic
    fun rollDiceWithModifier(diceValue: Int, diceToRoll: Int, modifier: Int): Int {
        return rollDice(diceValue, diceToRoll) + modifier
    }

    @JvmStatic
    fun rollDice(diceValue: Int, diceToRoll: Int): Int {
        var rollTotal = 0
        val rand: Random = ThreadLocalRandom.current()
        for (i in 0 until diceToRoll) {
            val roll = rand.nextInt(diceValue) + 1 //This results in 1 - 20 (instead of 0 - 19) for default value
            rollTotal += roll
        }
        return rollTotal
    }


    @JvmStatic
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

    @JvmStatic
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

    @JvmStatic
    val initButtons: TableButtons
        get() {
            val prevEmoji = Emoji.fromUnicode("⬅️")
            val xEmoji = Emoji.fromUnicode("❌")
            val nextEmoji = Emoji.fromUnicode("➡️")
            val prev = Button.primary("init:prev", prevEmoji)
            val clear = Button.primary("init:clear", xEmoji)
            val next = Button.primary("init:next", nextEmoji)
            return TableButtons(prev, clear, next)
        }

    @JvmStatic
    fun sendOrEditInitiativeMessage(
        hook: InteractionHook,
        embedBuilder: EmbedBuilder,
        event: ButtonInteractionEvent?,
        deleteDelay: Int?
    ) {
        val initButtons = initButtons
        val messageEmbed = embedBuilder.build()
        if (event == null) {
            hook.sendMessageEmbeds(messageEmbed).setActionRow(initButtons.prev, initButtons.clear, initButtons.next).queue()
        } else {
            val message = event.message
            // We came via a button press, so edit the embed
            message.editMessageEmbeds(messageEmbed).setActionRow(initButtons.prev, initButtons.clear, initButtons.next)
                .queue()
            hook.setEphemeral(true).sendMessageFormat("Next turn: %s", sortedEntries[initiativeIndex.get()].key).queue(
                invokeDeleteOnMessageResponse(
                    deleteDelay!!
                )
            )
        }
    }


    @JvmStatic
    val initiativeEmbedBuilder: EmbedBuilder
        get() {
            val embedBuilder = EmbedBuilder()
            embedBuilder.setColor(Color.GREEN)
            embedBuilder.setTitle("Initiative Order")
            val description = createTurnOrderString()
            embedBuilder.setDescription(description.toString())
            return embedBuilder
        }

    @JvmStatic
    fun clearInitiative() {
        initiativeIndex.plain = 0
        sortedEntries.clear()
    }

    fun clearInitiative(hook: InteractionHook, event: ButtonInteractionEvent) {
        val message = event.message
        message.delete().queue()
        initiativeIndex.plain = 0
        sortedEntries.clear()
        hook.deleteOriginal().queue()
    }


    @JvmRecord
    data class TableButtons(@JvmField val prev: Button, @JvmField val clear: Button, @JvmField val next: Button)
}
