package bot.toby

import bot.toby.handler.EventWaiter
import bot.toby.handler.MessageEventHandler
import bot.toby.handler.StartUpHandler
import bot.toby.handler.VoiceEventHandler
import bot.toby.helpers.IntroHelper
import bot.toby.helpers.UserDtoHelper
import core.managers.ButtonManager
import core.managers.CommandManager
import core.managers.MenuManager
import database.service.ConfigService
import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
open class BotMain @Autowired constructor(
    jda: JDA,
    configService: ConfigService,
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