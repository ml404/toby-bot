package toby.menu.menus

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import toby.command.CommandTest.Companion.interactionHook
import toby.helpers.IntroHelper
import toby.helpers.UserDtoHelper
import toby.jpa.dto.MusicDto
import toby.jpa.dto.UserDto
import toby.menu.MenuContext
import toby.menu.MenuTest
import toby.menu.MenuTest.Companion.menuEvent

internal class IntroMenuTest : MenuTest {

    private lateinit var introMenu: IntroMenu
    private lateinit var introHelper: IntroHelper
    private lateinit var userDtoHelper: UserDtoHelper

    private lateinit var menuContext: MenuContext

    @BeforeEach
    fun setUp() {
        setUpMenuMocks()

        // Mock the dependencies
        introHelper = mockk(relaxed = true)
        userDtoHelper = mockk(relaxed = true)

        // Initialize the class under test
        introMenu = IntroMenu(introHelper, userDtoHelper)

        // Mock the context
        menuContext = mockk(relaxed = true)
        every { menuContext.selectEvent } returns menuEvent
    }

    @Test
    fun `test handle with valid selection and pending intro`() {
        // Arrange
        val userDto = mockk<UserDto>(relaxed = true) {
            every { discordId } returns 1234L
            every { musicDtos } returns mutableListOf(
                mockk<MusicDto>(relaxed = true) { every { id } returns "1" },
                mockk<MusicDto>(relaxed = true) { every { id } returns "2" }
            )
        }

        val guild = mockk<Guild> {
            every { idLong } returns 1L
        }
        val jdaUser = mockk<User> {
            every { idLong } returns 1234L
            every { effectiveName } returns "Effective Name"
        }

        val member = mockk<Member> {
            every { isOwner } returns true
            every { effectiveName } returns "Effective Name"
            every { idLong } returns 123L
        }

        every { menuEvent.guild } returns guild
        every { menuEvent.member } returns member
        every { menuEvent.user } returns jdaUser
        every { menuEvent.selectedOptions } returns listOf(mockk {
            every { value } returns "2" // Represents a valid musicDtoId, not a list index
        })

        every { userDtoHelper.calculateUserDto(1L, 1234L, true) } returns userDto
        val musicDtoToReplace = userDto.musicDtos.first { it.id == "2" } // Match the selectedMusicDtoId
        every { introHelper.pendingIntros[1234L] } returns Triple(mockk(), "url", 50)

        // Act
        introMenu.handle(menuContext, 10)

        // Assert
        verify {
            introHelper.handleMedia(
                menuEvent,
                userDto,
                10,
                any(), // pendingDtoAttachment
                "url",
                50,
                musicDtoToReplace, // Ensure we are using the correct MusicDto
                "Effective Name"
            )
        }
    }


    @Test
    fun `test handle with invalid selection`() {
        // Arrange
        every { menuEvent.selectedOptions } returns listOf(mockk {
            every { value } returns "invalid"
        })

        // Act
        introMenu.handle(menuContext, 10)

        // Assert
        verify {
            interactionHook.sendMessage("Invalid selection or user data. Please try again.")
        }
    }

    @Test
    fun `test handle with no pending intro`() {
        // Arrange
        val userDto = mockk<UserDto>(relaxed = true) {
            every { discordId } returns 1234L
            every { musicDtos } returns mutableListOf(mockk(relaxed = true), mockk(relaxed = true))
        }

        val guild = mockk<Guild> {
            every { idLong } returns 1L
        }
        val jdaUser = mockk<User> {
            every { idLong } returns 1234L
            every { effectiveName } returns "Effective Name"
        }

        val member = mockk<Member> {
            every { isOwner } returns true
            every { effectiveName } returns "Effective Name"
            every { user } returns jdaUser
            every { idLong } returns 1234L

        }

        every { menuEvent.guild } returns guild
        every { menuEvent.member } returns member
        every { menuEvent.user } returns jdaUser
        every { menuEvent.selectedOptions } returns listOf(mockk {
            every { value } returns "1"
        })

        every { userDtoHelper.calculateUserDto(1L, 1234L, true) } returns userDto
        every { introHelper.pendingIntros[1234L] } returns null

        // Act
        introMenu.handle(menuContext, 10)

        // Assert
        verify(exactly = 0) {
            introHelper.handleMedia(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
