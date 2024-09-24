package toby.handler

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class EventWaiterTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `test message received and action invoked`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val eventWaiter = EventWaiter(dispatcher)

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
        advanceUntilIdle()

        // Verify the action was executed
        assertTrue(actionExecuted, "The action should be executed when the condition is met.")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `test message timeout`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val eventWaiter = EventWaiter(dispatcher)

        // Simulate the timeout condition
        var timeoutExecuted = false
        eventWaiter.waitForMessage(
            condition = { false },
            action = { },
            timeout = 100L,
            timeoutAction = { timeoutExecuted = true }
        )

        advanceUntilIdle()

        // Wait for the timeout to expire
        TimeUnit.MILLISECONDS.sleep(200)

        // Verify the timeout action was executed
        assertTrue(timeoutExecuted, "The timeout action should be executed after the timeout period.")
    }
}
