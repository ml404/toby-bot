package bot.toby.managers

import bot.toby.helpers.UserDtoHelper
import core.command.UserContextCommand
import database.dto.guild.ConfigDto
import database.dto.user.UserDto
import database.service.guild.ConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefaultUserContextManagerTest {

    private lateinit var configService: ConfigService
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var command: UserContextCommand
    private lateinit var manager: DefaultUserContextManager
    private lateinit var event: UserContextInteractionEvent

    private val userDto = UserDto(discordId = 1L, guildId = 7L)

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        userDtoHelper = mockk(relaxed = true)
        command = mockk(relaxed = true) {
            every { name } returns "View intros"
            every { ephemeral } returns true
            every { defersReply } returns true
        }
        manager = DefaultUserContextManager(configService, userDtoHelper, listOf(command))

        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns 7L
            every { id } returns "7"
        }
        val member = mockk<Member>(relaxed = true) { every { isOwner } returns false }
        event = mockk(relaxed = true) {
            every { this@mockk.guild } returns guild
            every { this@mockk.member } returns member
            every { user } returns mockk<User>(relaxed = true) { every { idLong } returns 1L }
            every { name } returns "View intros"
        }
        every { userDtoHelper.calculateUserDto(1L, 7L, false) } returns userDto
        every {
            configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "7")
        } returns ConfigDto().apply { value = "12" }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `lookup is case-insensitive on the display name`() {
        assertEquals(command, manager.getUserContextCommand("View intros"))
        assertEquals(command, manager.getUserContextCommand("view intros"))
        assertNull(manager.getUserContextCommand("Something else"))
    }

    @Test
    fun `a known command is deferred and dispatched with the guild delete delay`() {
        manager.handle(event)

        verify { event.deferReply(true) }
        verify { command.handle(event, userDto, 12) }
    }

    @Test
    fun `a command that opens a modal is dispatched without being acked first`() {
        // A modal has to be an interaction's first response, so deferring on
        // its behalf would make replyModal impossible.
        every { command.defersReply } returns false

        manager.handle(event)

        verify(exactly = 0) { event.deferReply(any()) }
        verify { command.handle(event, userDto, 12) }
    }

    @Test
    fun `an unknown command is ignored without acking the interaction`() {
        every { event.name } returns "Nope"

        manager.handle(event)

        verify(exactly = 0) { event.deferReply(any()) }
        verify(exactly = 0) { command.handle(any(), any(), any()) }
    }

    @Test
    fun `an interaction outside a guild is ignored`() {
        every { event.guild } returns null

        manager.handle(event)

        verify(exactly = 0) { command.handle(any(), any(), any()) }
    }

    @Test
    fun `an unparseable delete delay falls back to zero`() {
        every {
            configService.getConfigByName(ConfigDto.Configurations.DELETE_DELAY.configValue, "7")
        } returns ConfigDto().apply { value = "not-a-number" }

        manager.handle(event)

        verify { command.handle(event, userDto, 0) }
    }

    @Test
    fun `context command data is registered as a user entry, not a message one`() {
        // Registering it as a message command would put "View intros" behind a
        // right-click on a *message*, where there is no member to look up.
        every { command.commandData } returns
            net.dv8tion.jda.api.interactions.commands.build.Commands.user("View intros")

        assertEquals(1, manager.contextCommandData.size)
        assertEquals("View intros", manager.contextCommandData.single().name)
        assertEquals(
            net.dv8tion.jda.api.interactions.commands.Command.Type.USER,
            manager.contextCommandData.single().type,
        )
    }
}
