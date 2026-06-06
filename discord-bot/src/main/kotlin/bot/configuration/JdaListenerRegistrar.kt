package bot.configuration

import bot.toby.handler.ActivityEventHandler
import bot.toby.handler.AutocompleteEventListener
import bot.toby.handler.ButtonEventListener
import bot.toby.handler.CardMentionListener
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
import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class JdaListenerRegistrar @Autowired constructor(
    jda: JDA,
    startUpHandler: StartUpHandler,
    voiceEventHandler: VoiceEventHandler,
    messageChatListener: MessageChatListener,
    cardMentionListener: CardMentionListener,
    slashCommandEventListener: SlashCommandEventListener,
    buttonEventListener: ButtonEventListener,
    menuEventListener: MenuEventListener,
    modalEventListener: ModalEventListener,
    autocompleteEventListener: AutocompleteEventListener,
    activityEventHandler: ActivityEventHandler,
    eventWaiter: EventWaiter,
    guildLeaveCleanupHandler: GuildLeaveCleanupHandler,
    installWelcomeHandler: InstallWelcomeHandler,
    welcomeAndAutoRoleHandler: WelcomeAndAutoRoleHandler,
) {
    init {
        jda.addEventListener(
            startUpHandler,
            voiceEventHandler,
            messageChatListener,
            cardMentionListener,
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
