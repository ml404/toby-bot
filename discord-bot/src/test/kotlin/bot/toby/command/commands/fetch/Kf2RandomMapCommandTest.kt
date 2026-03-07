package bot.toby.command.commands.fetch

import bot.toby.command.CommandTest
import bot.toby.command.CommandTest.Companion.event
import bot.toby.command.DefaultCommandContext
import bot.toby.helpers.WikiFetcher
import common.helpers.Cache
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

class Kf2RandomMapCommandTest : CommandTest {

    private val cache: Cache = mockk(relaxed = true)
    private lateinit var command: Kf2RandomMapCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        mockkConstructor(WikiFetcher::class)
        command = Kf2RandomMapCommand(cache)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkConstructor(WikiFetcher::class)
    }

    @Test
    fun `handle sends a random map name on success`() {
        every {
            anyConstructed<WikiFetcher>().fetchFromWiki(any(), any(), any(), any())
        } returns listOf("Biotics Lab", "Burning Paris", "Farmhouse")

        command.handle(DefaultCommandContext(event), CommandTest.requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(any<String>()) }
    }

    @Test
    fun `handle sends error message when WikiFetcher throws IOException`() {
        every {
            anyConstructed<WikiFetcher>().fetchFromWiki(any(), any(), any(), any())
        } throws IOException("Connection failed")

        command.handle(DefaultCommandContext(event), CommandTest.requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessage(match<String> { it.contains("unexpected") }) }
    }

    @Test
    fun `name is kf2`() {
        assertEquals("kf2", command.name)
    }

    @Test
    fun `description describes the command`() {
        assertEquals("return a random kf2 map", command.description)
    }
}
