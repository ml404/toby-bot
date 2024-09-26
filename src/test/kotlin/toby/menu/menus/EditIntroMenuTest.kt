package toby.menu.menus

import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.handler.EventWaiter
import toby.helpers.IntroHelper
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.menu.MenuContext
import toby.menu.MenuTest
import toby.menu.MenuTest.Companion.menuEvent

class EditIntroMenuTest : MenuTest {

    private lateinit var introHelper: IntroHelper
    private lateinit var eventWaiter: EventWaiter
    private lateinit var editIntroMenu: EditIntroMenu
    private lateinit var menuContext: MenuContext
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
        editIntroMenu = EditIntroMenu(introHelper, eventWaiter)

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
        editIntroMenu.handle(menuContext, 0)

        // Verify that the user is prompted for a new volume
        verify { menuContext.event.hook.sendMessage("You've selected Intro1. Please reply with the new volume (0-100).") }
    }

    @Test
    fun `test invalid intro selection`() {
        // Return null when trying to find the intro
        every { introHelper.findIntroById("1") } returns null
        every { menuContext.event.values.firstOrNull() } returns "1"

        // Call handle
        editIntroMenu.handle(menuContext, 0)

        // Verify that the user is informed the intro wasn't found
        verify { menuContext.event.hook.sendMessage("Unable to find the selected intro.") }
    }
}
