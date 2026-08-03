package bot.toby.menu.menus

import bot.toby.helpers.IntroHelper
import bot.toby.menu.DefaultMenuContext
import bot.toby.menu.MenuTest
import bot.toby.menu.MenuTest.Companion.menuEvent
import database.dto.music.MusicDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `EditIntroMenu` responds to the component interaction directly (a modal has
 * to be the first response), and JDA's `reply`/`replyModal` are default
 * interface methods that the shared event mock doesn't intercept — so these
 * assert on the collaborator side. The modal's contents and submit behaviour
 * are covered by `EditIntroModalTest`.
 */
class EditIntroMenuTest : MenuTest {

    private lateinit var introHelper: IntroHelper
    private lateinit var editIntroMenu: EditIntroMenu
    private lateinit var menuContext: DefaultMenuContext
    private val userDto: database.dto.user.UserDto = mockk {
        every { discordId } returns 1234L
        every { guildId } returns 4567L
    }

    @BeforeEach
    fun setUp() {
        setUpMenuMocks()

        introHelper = mockk(relaxed = true)
        editIntroMenu = EditIntroMenu(introHelper)

        menuContext = mockk(relaxed = true)
        every { menuContext.event } returns menuEvent
    }

    @AfterEach
    fun tearDown() {
        tearDownMenuMocks()
        unmockkAll()
    }

    @Test
    fun `resolves the selected intro before opening the editor`() {
        val intro = MusicDto(userDto, 1, "Intro1", 42, "https://example.com/a".toByteArray(), 3_000, 9_000)
        every { introHelper.findIntroById("4567_1234_1") } returns intro
        every { menuContext.event.values.firstOrNull() } returns "4567_1234_1"

        editIntroMenu.handle(menuContext, 0)

        verify { introHelper.findIntroById("4567_1234_1") }
        // The old flow mutated the intro straight from the menu; the modal is
        // now the only thing that writes.
        verify(exactly = 0) { introHelper.updateIntro(any()) }
    }

    @Test
    fun `unknown intro does not update anything`() {
        every { introHelper.findIntroById("1") } returns null
        every { menuContext.event.values.firstOrNull() } returns "1"

        editIntroMenu.handle(menuContext, 0)

        verify(exactly = 0) { introHelper.updateIntro(any()) }
    }

    @Test
    fun `empty selection is ignored`() {
        every { menuContext.event.values.firstOrNull() } returns null

        editIntroMenu.handle(menuContext, 0)

        verify(exactly = 0) { introHelper.findIntroById(any()) }
    }
}
