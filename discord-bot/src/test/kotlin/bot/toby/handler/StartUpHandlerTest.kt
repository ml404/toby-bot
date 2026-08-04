import bot.toby.handler.GlobalCommandRegistrar
import bot.toby.handler.StartUpHandler
import bot.toby.helpers.UserDtoHelper
import bot.toby.managers.DefaultCommandManager
import core.managers.MessageContextManager
import core.managers.UserContextManager
import database.service.guild.ConfigService
import database.service.social.SocialCreditAwardService
import io.mockk.*
import io.mockk.junit5.MockKExtension
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.SelfUser
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CompletableFuture

@ExtendWith(MockKExtension::class)
class StartUpHandlerTest {
    private val jda: JDA = mockk()
    private val configService: ConfigService = mockk()
    private val userDtoHelper: UserDtoHelper = mockk()
    private val awardService: SocialCreditAwardService = mockk(relaxed = true)
    private val commandManager: DefaultCommandManager =
        DefaultCommandManager(configService, userDtoHelper, awardService, emptyList())
    private val globalCommandRegistrar: GlobalCommandRegistrar = mockk(relaxed = true)
    private val contextCommandData: List<CommandData> = listOf(Commands.message("Set as my intro"))
    private val userContextCommandData: List<CommandData> = listOf(Commands.user("View intros"))
    private val messageContextManager: MessageContextManager = mockk(relaxed = true) {
        every { this@mockk.contextCommandData } returns this@StartUpHandlerTest.contextCommandData
    }
    private val userContextManager: UserContextManager = mockk(relaxed = true) {
        every { this@mockk.contextCommandData } returns this@StartUpHandlerTest.userContextCommandData
    }
    private val handler = spyk(
        StartUpHandler(
            commandManager,
            messageContextManager,
            userContextManager,
            globalCommandRegistrar
        )
    )

    private fun readyEvent(): ReadyEvent {
        val selfUser = mockk<SelfUser> { every { name } returns "TobyBot" }
        every { jda.selfUser } returns selfUser
        every { jda.guildCache } returns mockk<SnowflakeCacheView<Guild>>()
        return mockk { every { this@mockk.jda } returns this@StartUpHandlerTest.jda }
    }

    @Test
    fun `onReady registers slash commands and context menu entries in one overwrite`() {
        val submitted = slot<List<CommandData>>()
        every { globalCommandRegistrar.register(jda, capture(submitted)) } returns
            CompletableFuture.completedFuture(true)

        handler.onReady(readyEvent())

        verify(exactly = 1) { globalCommandRegistrar.register(jda, any()) }
        // All three surfaces share the global command set: sending them as
        // separate overwrites would have each delete the one before it.
        assertTrue(submitted.captured.any { it.name == "Set as my intro" })
        assertTrue(submitted.captured.any { it.name == "View intros" })
        assertEquals(
            commandManager.allSlashCommands.size + contextCommandData.size + userContextCommandData.size,
            submitted.captured.size,
        )
    }

    @Test
    fun `onReady never calls JDA's bulk update directly`() {
        // jda.updateCommands() can't express the Activity Entry Point command,
        // and Discord rejects the whole request when one would be dropped —
        // see GlobalCommandRegistrar.
        every { globalCommandRegistrar.register(any(), any()) } returns
            CompletableFuture.completedFuture(true)

        handler.onReady(readyEvent())

        verify(exactly = 0) { jda.updateCommands() }
    }
}
