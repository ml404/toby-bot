import bot.toby.handler.ActivityEntryPointRegistrar
import bot.toby.handler.StartUpHandler
import bot.toby.helpers.UserDtoHelper
import bot.toby.managers.DefaultCommandManager
import database.service.guild.ConfigService
import database.service.social.SocialCreditAwardService
import io.mockk.*
import io.mockk.junit5.MockKExtension
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.SelfUser
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.function.Consumer

@ExtendWith(MockKExtension::class)
class StartUpHandlerTest {
    private val jda: JDA = mockk()
    private val configService: ConfigService = mockk()
    private val userDtoHelper: UserDtoHelper = mockk()
    private val awardService: SocialCreditAwardService = mockk(relaxed = true)
    private val commandManager: DefaultCommandManager = DefaultCommandManager(configService, userDtoHelper, awardService, emptyList())
    private val entryPointRegistrar: ActivityEntryPointRegistrar = mockk(relaxed = true)
    private val handler = spyk(
        StartUpHandler(
            commandManager,
            entryPointRegistrar
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
        every { commandListUpdateAction.queue(any()) } just Runs


        // Act
        handler.onReady(readyEvent)

        // Assert individual steps
        verify(exactly = 1) { jda.updateCommands() }
        verify(exactly = 1) { commandListUpdateAction.addCommands(commandManager.allSlashCommands) }
        verify(exactly = 1) { commandListUpdateAction.queue(any()) }
    }

    @Test
    fun `onReady re-registers the activity entry point command after the bulk overwrite lands`() {
        // The bulk update REPLACES the global command set (deleting the
        // entry point command), so the registrar must only fire from the
        // success callback — never before.
        val readyEvent = mockk<ReadyEvent>()
        val selfUser = mockk<SelfUser>()
        val commandListUpdateAction = mockk<CommandListUpdateAction>()
        val successCallback = slot<Consumer<List<Command>>>()

        every { readyEvent.jda } returns jda
        every { jda.selfUser } returns selfUser
        every { selfUser.name } returns "TobyBot"
        every { jda.updateCommands() } returns commandListUpdateAction
        every { commandListUpdateAction.addCommands(commandManager.allSlashCommands) } returns commandListUpdateAction
        every { commandListUpdateAction.queue(capture(successCallback)) } just Runs

        handler.onReady(readyEvent)

        // Not yet — the overwrite hasn't completed.
        verify(exactly = 0) { entryPointRegistrar.register(any()) }

        successCallback.captured.accept(emptyList())

        verify(exactly = 1) { entryPointRegistrar.register(jda) }
    }

}
