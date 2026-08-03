package bot.toby.managers

import bot.toby.helpers.UserDtoHelper
import core.command.MessageContextCommand
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
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DefaultMessageContextManagerTest {

    private lateinit var configService: ConfigService
    private lateinit var userDtoHelper: UserDtoHelper
    private lateinit var command: MessageContextCommand
    private lateinit var manager: DefaultMessageContextManager
    private lateinit var event: MessageContextInteractionEvent

    private val userDto = UserDto(discordId = 1L, guildId = 7L)

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        userDtoHelper = mockk(relaxed = true)
        command = mockk(relaxed = true) {
            every { name } returns "Set as my intro"
            every { ephemeral } returns true
        }
        manager = DefaultMessageContextManager(configService, userDtoHelper, listOf(command))

        val guild = mockk<Guild>(relaxed = true) {
            every { idLong } returns 7L
            every { id } returns "7"
        }
        val member = mockk<Member>(relaxed = true) { every { isOwner } returns false }
        event = mockk(relaxed = true) {
            every { this@mockk.guild } returns guild
            every { this@mockk.member } returns member
            every { user } returns mockk<User>(relaxed = true) { every { idLong } returns 1L }
            every { name } returns "Set as my intro"
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
        assertEquals(command, manager.getMessageContextCommand("Set as my intro"))
        assertEquals(command, manager.getMessageContextCommand("set as my intro"))
        assertNull(manager.getMessageContextCommand("Something else"))
    }

    @Test
    fun `a known command is deferred and dispatched with the guild delete delay`() {
        manager.handle(event)

        verify { event.deferReply(true) }
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
    fun `context command data is derived from the registered commands`() {
        every { command.commandData } returns
            net.dv8tion.jda.api.interactions.commands.build.Commands.message("Set as my intro")

        assertEquals(1, manager.contextCommandData.size)
        assertEquals("Set as my intro", manager.contextCommandData.single().name)
    }
}
