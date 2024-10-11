package bot.toby

import bot.database.service.IConfigService
import bot.toby.handler.EventWaiter
import bot.toby.handler.MessageEventHandler
import bot.toby.handler.StartUpHandler
import bot.toby.handler.VoiceEventHandler
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.UserDtoHelper
import bot.toby.managers.ButtonManager
import bot.toby.managers.CommandManager
import bot.toby.managers.MenuManager
import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
open class BotMain @Autowired constructor(
    jda: JDA,
    configService: IConfigService,
    userDtoHelper: UserDtoHelper,
    commandManager: CommandManager,
    buttonManager: ButtonManager,
    menuManager: MenuManager,
    eventWaiter: EventWaiter,
    introHelper: IntroHelper
) {
    init {
        jda.addEventListener(
            StartUpHandler(
                jda,
                commandManager
            )
        )
        jda.addEventListener(VoiceEventHandler(jda, configService, userDtoHelper, introHelper))
        jda.addEventListener(
            MessageEventHandler(
                jda,
                commandManager,
                buttonManager,
                menuManager
            )
        )
        jda.addEventListener(eventWaiter)
    }
}