package bot.toby.button.buttons.mtg

import bot.toby.button.ButtonTest
import bot.toby.button.ButtonTest.Companion.event
import bot.toby.button.DefaultButtonContext
import bot.toby.command.commands.mtg.CardCommand
import bot.toby.command.commands.mtg.ScryfallCubeFetcher
import common.mtg.CubeCard
import common.mtg.MtgColor
import database.dto.user.UserDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CardSearchPageButtonTest : ButtonTest {

    private lateinit var fetcher: ScryfallCubeFetcher
    private lateinit var button: CardSearchPageButton

    @BeforeEach
    override fun setup() {
        super.setup()
        fetcher = mockk()
        // Unconfined so the button's launched coroutine runs synchronously.
        button = CardSearchPageButton(fetcher, Dispatchers.Unconfined)
    }

    @AfterEach
    override fun tearDown() = super.tearDown()

    private fun run() = button.handle(DefaultButtonContext(event), UserDto(1L, 1L), 0)

    @Test
    fun `re-runs the encoded query and renders the requested page`() {
        every { event.componentId } returns CardCommand.encodeSearchButton("\"iron man\" t:creature", 1)
        coEvery { fetcher.fetch("\"iron man\" t:creature", CardCommand.SEARCH_MAX) } returns
            ScryfallCubeFetcher.Result.Success(
                listOf(
                    CubeCard("Iron Man, Armored Avenger", setOf(MtgColor.WHITE), typeLine = "Legendary Artifact Creature"),
                    CubeCard("Iron Man, Bleeding Edge", setOf(MtgColor.BLUE), typeLine = "Legendary Artifact Creature"),
                ),
            )

        run()

        coVerify { fetcher.fetch("\"iron man\" t:creature", CardCommand.SEARCH_MAX) }
    }

    @Test
    fun `ignores the disabled page-indicator button`() {
        every { event.componentId } returns "${CardCommand.SEARCH_BUTTON}:noop"

        run()

        coVerify(exactly = 0) { fetcher.fetch(any(), any()) }
    }

    @Test
    fun `ignores an unparseable component id`() {
        every { event.componentId } returns "${CardCommand.SEARCH_BUTTON}:notanumber:xxx"

        run()

        coVerify(exactly = 0) { fetcher.fetch(any(), any()) }
    }

    @Test
    fun `edits the source message rather than replying`() {
        // Regression guard: the paginator must ack via deferEdit (defersEdit),
        // otherwise the async re-render would fire on an unacknowledged button.
        assert(button.defersEdit) { "CardSearchPageButton must defer as an edit" }
    }
}
