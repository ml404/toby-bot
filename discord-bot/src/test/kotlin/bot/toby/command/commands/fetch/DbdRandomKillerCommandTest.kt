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

class DbdRandomKillerCommandTest : CommandTest {

    private val cache: Cache = mockk(relaxed = true)
    private lateinit var command: DbdRandomKillerCommand

    @BeforeEach
    fun setUp() {
        setUpCommonMocks()
        mockkConstructor(WikiFetcher::class)
        command = DbdRandomKillerCommand(cache)
    }

    @AfterEach
    fun tearDown() {
        tearDownCommonMocks()
        unmockkConstructor(WikiFetcher::class)
    }

    @Test
    fun `handle sends a random killer name on success`() {
        every {
            anyConstructed<WikiFetcher>().fetchFromWiki(any(), any(), any(), any())
        } returns listOf("The Trapper", "The Wraith", "The Hillbilly")

        command.handle(DefaultCommandContext(event), CommandTest.requestingUserDto, 0)

        verify(exactly = 1) { event.hook.sendMessageFormat(any(), *anyVararg()) }
    }

    @Test
    fun `handle sends error message when WikiFetcher throws IOException`() {
        every {
            anyConstructed<WikiFetcher>().fetchFromWiki(any(), any(), any(), any())
        } throws IOException("Connection failed")

        command.handle(DefaultCommandContext(event), CommandTest.requestingUserDto, 0)

        verify(exactly = 1) {
            event.hook.sendMessageFormat(
                match { it.contains("unexpected") },
                *anyVararg()
            )
        }
    }

    @Test
    fun `name is dbd-killer`() {
        assertEquals("dbd-killer", command.name)
    }

    @Test
    fun `description describes the command`() {
        assertEquals("return a random dead by daylight killer", command.description)
    }
}
