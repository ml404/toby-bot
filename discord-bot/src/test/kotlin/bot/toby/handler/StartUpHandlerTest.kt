import bot.toby.handler.StartUpHandler
import bot.toby.helpers.UserDtoHelper
import bot.toby.managers.DefaultCommandManager
import database.service.ConfigService
import io.mockk.*
import io.mockk.junit5.MockKExtension
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.SelfUser
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class StartUpHandlerTest {
    private val jda: JDA = mockk()
    private val configService: ConfigService = mockk()
    private val userDtoHelper: UserDtoHelper = mockk()
    private val commandManager: DefaultCommandManager = DefaultCommandManager(configService, userDtoHelper, emptyList())
    private val handler = spyk(
        StartUpHandler(
            jda,
            commandManager
        )
    )

    @Test
    fun `onReady should add commands to the JDA`() {
        // Arrange
        val readyEvent = mockk<ReadyEvent>()
        val selfUser = mockk<SelfUser>()
        val guildCache = mockk<SnowflakeCacheView<Guild>>()
        val commandListUpdateAction = mockk<CommandListUpdateAction>()

        // Mock the JDA and event
        every { readyEvent.jda } returns jda
        every { jda.selfUser } returns selfUser
        every { selfUser.name } returns "TobyBot"
        every { jda.guildCache } returns guildCache
        every { jda.updateCommands() } returns commandListUpdateAction
        every { commandListUpdateAction.addCommands(commandManager.allSlashCommands) } returns commandListUpdateAction
        every { commandListUpdateAction.queue() } just Runs


        // Act
        handler.onReady(readyEvent)

        // Assert individual steps
        verify(exactly = 1) { jda.updateCommands() }
        verify(exactly = 1) { commandListUpdateAction.addCommands(commandManager.allSlashCommands) }
        verify(exactly = 1) { commandListUpdateAction.queue() }
    }

}
