package toby.handler

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class EventWaiterTest {

    @Test
    fun `test message received and action invoked`() {
        val eventWaiter = EventWaiter()

        // Mock the event
        val mockEvent: MessageReceivedEvent = mockk()
        every { mockEvent.author.idLong } returns 12345L

        // Simulate the waiting condition and action
        var actionExecuted = false
        eventWaiter.waitForMessage(
            condition = { it.author.idLong == 12345L },
            action = { actionExecuted = true },
            timeout = 1000L,
            timeoutAction = {}
        )

        // Simulate event being received
        eventWaiter.onMessageReceived(mockEvent)

        // Verify the action was executed
        assertTrue(actionExecuted, "The action should be executed when the condition is met.")
    }

    @Test
    fun `test message timeout`() {
        val eventWaiter = EventWaiter()

        // Simulate the timeout condition
        var timeoutExecuted = false
        eventWaiter.waitForMessage(
            condition = { false },
            action = { },
            timeout = 100L,
            timeoutAction = { timeoutExecuted = true }
        )

        // Wait for the timeout to expire
        TimeUnit.MILLISECONDS.sleep(200)

        // Verify the timeout action was executed
        assertTrue(timeoutExecuted, "The timeout action should be executed after the timeout period.")
    }
}
