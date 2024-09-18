package toby

import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import toby.handler.MessageEventHandler
import toby.handler.StartUpHandler
import toby.handler.VoiceEventHandler
import toby.helpers.UserDtoHelper
import toby.jpa.service.IConfigService
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
    menuManager: MenuManager
) {
    init {
        jda.addEventListener(
            StartUpHandler(
                jda,
                commandManager
            )
        )
        jda.addEventListener(VoiceEventHandler(jda, configService, userDtoHelper))
        jda.addEventListener(
            MessageEventHandler(
                jda,
                commandManager,
                buttonManager,
                menuManager
            )
        )
    }
}