package toby

import database.service.IConfigService
import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import toby.handler.EventWaiter
import toby.handler.MessageEventHandler
import toby.handler.StartUpHandler
import toby.handler.VoiceEventHandler
import toby.helpers.IntroHelper
import toby.helpers.UserDtoHelper
import toby.managers.ButtonManager
import toby.managers.CommandManager
import toby.managers.MenuManager

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