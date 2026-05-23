package bot.configuration

import bot.toby.handler.ActivityEventHandler
import bot.toby.handler.AutocompleteEventListener
import bot.toby.handler.ButtonEventListener
import bot.toby.handler.EventWaiter
import bot.toby.handler.GuildLeaveCleanupHandler
import bot.toby.handler.MenuEventListener
import bot.toby.handler.MessageChatListener
import bot.toby.handler.ModalEventListener
import bot.toby.handler.SlashCommandEventListener
import bot.toby.handler.StartUpHandler
import bot.toby.handler.VoiceEventHandler
import bot.toby.handler.WelcomeAndAutoRoleHandler
import bot.toby.install.InstallWelcomeHandler
import io.mockk.mockk
import io.mockk.verify
import net.dv8tion.jda.api.JDA
import org.junit.jupiter.api.Test

class JdaListenerRegistrarTest {

    @Test
    fun `should register all event listeners with JDA`() {
        val jda = mockk<JDA>(relaxed = true)
        val startUpHandler = mockk<StartUpHandler>()
        val voiceEventHandler = mockk<VoiceEventHandler>()
        val messageChatListener = mockk<MessageChatListener>()
        val slashCommandEventListener = mockk<SlashCommandEventListener>()
        val buttonEventListener = mockk<ButtonEventListener>()
        val menuEventListener = mockk<MenuEventListener>()
        val modalEventListener = mockk<ModalEventListener>()
        val autocompleteEventListener = mockk<AutocompleteEventListener>()
        val activityEventHandler = mockk<ActivityEventHandler>()
        val eventWaiter = mockk<EventWaiter>()
        val guildLeaveCleanupHandler = mockk<GuildLeaveCleanupHandler>()
        val installWelcomeHandler = mockk<InstallWelcomeHandler>()
        val welcomeAndAutoRoleHandler = mockk<WelcomeAndAutoRoleHandler>()

        JdaListenerRegistrar(
            jda,
            startUpHandler,
            voiceEventHandler,
            messageChatListener,
            slashCommandEventListener,
            buttonEventListener,
            menuEventListener,
            modalEventListener,
            autocompleteEventListener,
            activityEventHandler,
            eventWaiter,
            guildLeaveCleanupHandler,
            installWelcomeHandler,
            welcomeAndAutoRoleHandler,
        )

        verify {
            jda.addEventListener(
                startUpHandler,
                voiceEventHandler,
                messageChatListener,
                slashCommandEventListener,
                buttonEventListener,
                menuEventListener,
                modalEventListener,
                autocompleteEventListener,
                activityEventHandler,
                eventWaiter,
                guildLeaveCleanupHandler,
                installWelcomeHandler,
                welcomeAndAutoRoleHandler,
            )
        }
    }
}
