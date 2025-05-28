package bot.toby.menu.menus

import bot.toby.handler.EventWaiter
import bot.toby.helpers.IntroHelper
import bot.toby.menu.DefaultMenuContext
import bot.toby.menu.MenuTest
import bot.toby.menu.MenuTest.Companion.menuEvent
import database.dto.MusicDto
import database.dto.UserDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeleteIntroMenuTest : MenuTest {

    private lateinit var introHelper: IntroHelper
    private lateinit var eventWaiter: EventWaiter
    private lateinit var deleteIntroMenu: DeleteIntroMenu
    private lateinit var menuContext: DefaultMenuContext
    private val userDto: UserDto = mockk {
        every { discordId } returns 1234L
        every { guildId } returns 4567L
    }

    @BeforeEach
    fun setUp() {
        setUpMenuMocks()

        // Mock the dependencies
        introHelper = mockk(relaxed = true)
        eventWaiter = mockk(relaxed = true)

        // Initialize the class under test
        deleteIntroMenu = DeleteIntroMenu(introHelper)

        // Mock the context
        menuContext = mockk(relaxed = true)
        every { menuContext.event } returns menuEvent
    }

    @AfterEach
    fun tearDown(){
        tearDownMenuMocks()
        unmockkAll()
    }

    @Test
    fun `test valid intro selection`() {
        val intro = MusicDto(userDto, 1, "Intro1")
        every { introHelper.findIntroById("1") } returns intro
        every { menuContext.event.values.firstOrNull() } returns "1"

        // Call handle
        deleteIntroMenu.handle(menuContext, 0)

        // Verify that intro is deleted
        verify { introHelper.deleteIntro(intro) }
    }

    @Test
    fun `test invalid intro selection`() {
        // Return null when trying to find the intro
        every { introHelper.findIntroById("1") } returns null
        every { menuContext.event.values.firstOrNull() } returns "1"

        // Call handle
        deleteIntroMenu.handle(menuContext, 0)

        // Verify that the user is informed the intro wasn't found
        verify { menuContext.event.hook.sendMessage("Unable to find the selected intro.") }
    }
}
