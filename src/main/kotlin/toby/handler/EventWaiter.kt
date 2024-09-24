package toby.handler

import kotlinx.coroutines.*
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class EventWaiter(val dispatcher: CoroutineDispatcher = Dispatchers.Default) : ListenerAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val waitingEvents = mutableListOf<WaitingEvent<MessageReceivedEvent>>()

    // Function to add an event listener
    fun waitForMessage(
        condition: (MessageReceivedEvent) -> Boolean,
        action: (MessageReceivedEvent) -> Unit,
        timeout: Long,
        timeoutAction: () -> Unit
    ) {
        val waitingEvent = WaitingEvent(condition, action, timeoutAction)
        waitingEvents.add(waitingEvent)

        // Non-blocking timeout logic using coroutines
        scope.launch {
            delay(timeout)
            if (waitingEvents.contains(waitingEvent)) {
                waitingEvents.remove(waitingEvent)
                timeoutAction.invoke()
            }
        }
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        val iterator = waitingEvents.iterator()
        while (iterator.hasNext()) {
            val waitingEvent = iterator.next()
            if (waitingEvent.condition.invoke(event)) {
                waitingEvent.action.invoke(event)
                iterator.remove()  // Remove the event after handling
            }
        }
    }

    // Data class to hold waiting events
    private data class WaitingEvent<T>(
        val condition: (T) -> Boolean,
        val action: (T) -> Unit,
        val timeoutAction: () -> Unit
    )
}
