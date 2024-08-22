package toby.menu.menus.dnd

import coroutines.MainCoroutineExtension
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import toby.command.commands.fetch.TestHttpHelperHelper.ACTION_SURGE_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.COVER_INITIAL_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.FIREBALL_INITIAL_RESPONSE
import toby.command.commands.fetch.TestHttpHelperHelper.GRAPPLED_INITIAL_RESPONSE
import toby.helpers.HttpHelper

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class DndApiCoroutineHandlerTest {

    private lateinit var handler: DndApiCoroutineHandler
    private lateinit var httpHelper: HttpHelper

    @BeforeEach
    fun setup() {
        httpHelper = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `launchFetchAndSendEmbed should handle network response correctly`() = runTest {
        // Set up mocks
        val event = mockk<StringSelectInteractionEvent>(relaxed = true)
        val hook = mockk<InteractionHook>(relaxed = true)

        // Set up mock behaviors
        every { event.values.firstOrNull() } returns "fireball"
        coEvery { httpHelper.fetchFromGet(any()) } returns FIREBALL_INITIAL_RESPONSE
        every { hook.sendMessageEmbeds(any<MessageEmbed>()).queue() } returns Unit

        handler = DndApiCoroutineHandler(StandardTestDispatcher(testScheduler), httpHelper)

        // Act
        handler.launchFetchAndSendEmbed(event, "spell", "spells", hook)
        advanceUntilIdle()

        // Assert
        coVerify { hook.sendMessageEmbeds(any<MessageEmbed>()).queue() }
    }

    @Test
    fun `launchFetchAndSendEmbed should send message embed for rule when response is valid`() = runTest {
        // Arrange
        val event = mockk<StringSelectInteractionEvent>(relaxed = true)
        val hook = mockk<InteractionHook>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(any()) } returns COVER_INITIAL_RESPONSE
        every { hook.sendMessageEmbeds(any<MessageEmbed>()).queue() } returns Unit


        handler = DndApiCoroutineHandler(StandardTestDispatcher(testScheduler), httpHelper)

        every { event.values.firstOrNull() } returns "cover"

        // Act
        handler.launchFetchAndSendEmbed(event, "rule", "rule-sections", hook)
        advanceUntilIdle()

        // Assert
        coVerify { hook.sendMessageEmbeds(any<MessageEmbed>()).queue() }
    }

    @Test
    fun `launchFetchAndSendEmbed should send message embed for feature when response is valid`() = runTest {
        // Arrange
        val event = mockk<StringSelectInteractionEvent>(relaxed = true)
        val hook = mockk<InteractionHook>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(any()) } returns ACTION_SURGE_RESPONSE
        every { hook.sendMessageEmbeds(any<MessageEmbed>()).queue() } returns Unit


        handler = DndApiCoroutineHandler(StandardTestDispatcher(testScheduler), httpHelper)

        every { event.values.firstOrNull() } returns "action-surge-1-use"

        // Act
        handler.launchFetchAndSendEmbed(event, "feature", "features", hook)
        advanceUntilIdle()

        // Assert
        coVerify { hook.sendMessageEmbeds(any<MessageEmbed>()).queue() }
    }

    @Test
    fun `launchFetchAndSendEmbed should send message embed for condition when response is valid`() = runTest {
        // Arrange
        val event = mockk<StringSelectInteractionEvent>(relaxed = true)
        val hook = mockk<InteractionHook>(relaxed = true)
        coEvery { httpHelper.fetchFromGet(any()) } returns GRAPPLED_INITIAL_RESPONSE
        every { hook.sendMessageEmbeds(any<MessageEmbed>()).queue() } returns Unit

        handler = DndApiCoroutineHandler(StandardTestDispatcher(testScheduler), httpHelper)

        every { event.values.firstOrNull() } returns "grappled"

        // Act
        handler.launchFetchAndSendEmbed(event, "condition", "conditions", hook)
        advanceUntilIdle()

        // Assert
        coVerify { hook.sendMessageEmbeds(any<MessageEmbed>()).queue() }
    }
}