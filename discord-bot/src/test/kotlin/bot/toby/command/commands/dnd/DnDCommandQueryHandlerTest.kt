package bot.toby.command.commands.dnd

import bot.toby.command.commands.fetch.TestHttpHelperHelper.BLIND_QUERY_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.EMPTY_QUERY_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.ERROR_NOT_FOUND_RESPONSE
import bot.toby.command.commands.fetch.TestHttpHelperHelper.FIREBALL_INITIAL_RESPONSE
import bot.toby.helpers.DnDHelper
import bot.toby.helpers.HttpHelper
import bot.toby.helpers.UserDtoHelper
import coroutines.MainCoroutineExtension
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class DnDCommandQueryHandlerTest {

    private lateinit var queryHandler: DnDCommandQueryHandler
    private lateinit var httpHelper: HttpHelper
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var dndHelper: DnDHelper
    private val hook = mockk<InteractionHook>(relaxed = true)
    private val deleteDelay = 0

    @BeforeEach
    fun setUp() {
        userDtoHelper = mockk(relaxed = true)
        httpHelper = mockk(relaxed = true)
        dndHelper = DnDHelper(userDtoHelper)

    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle successful initial query and reply with embed`() = runTest {
        // Mock successful initial query response
        coEvery { httpHelper.fetchFromGet(any()) } returns FIREBALL_INITIAL_RESPONSE
        every { hook.sendMessageEmbeds(any<MessageEmbed>()).queue() } returns Unit

        val dispatcher = StandardTestDispatcher(testScheduler)
        queryHandler = DnDCommandQueryHandler(dispatcher, httpHelper, dndHelper, hook, deleteDelay)

        queryHandler.processQuery("spell", "spells", "fireball")

        advanceUntilIdle()

        // Verify interaction with the hook
        coVerify {
            hook.sendMessageEmbeds(any<MessageEmbed>()).queue()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle no initial results but successful followup scenario`() = runTest {
        // Mock initial and follow-up query responses
        coEvery { httpHelper.fetchFromGet(any()) } returns ERROR_NOT_FOUND_RESPONSE andThen BLIND_QUERY_RESPONSE
        every { hook.sendMessage(any<String>()).setActionRow(any<StringSelectMenu>()).queue() } returns Unit

        val dispatcher = StandardTestDispatcher(testScheduler)
        queryHandler = DnDCommandQueryHandler(dispatcher, httpHelper, dndHelper, hook, deleteDelay)

        queryHandler.processQuery("condition", "conditions", "blind")

        advanceUntilIdle()

        // Verify interaction with the hook for follow-up
        coVerify {
            hook.sendMessage(any<String>()).setActionRow(any<StringSelectMenu>()).queue()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should handle no results scenario`() = runTest {

        coEvery { httpHelper.fetchFromGet(any()) } returns ERROR_NOT_FOUND_RESPONSE andThen EMPTY_QUERY_RESPONSE
        every { hook.sendMessage(any<String>()).queue(any()) } returns Unit

        val dispatcher = StandardTestDispatcher(testScheduler)
        queryHandler = DnDCommandQueryHandler(dispatcher, httpHelper, dndHelper, hook, deleteDelay)

        queryHandler.processQuery("condition", "conditions", "bin")

        advanceUntilIdle()

        // Verify interaction with the hook for no results
        coVerify {
            hook.sendMessage(any<String>()).queue(any())
        }
    }
}
