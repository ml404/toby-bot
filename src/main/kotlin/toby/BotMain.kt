package toby

import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.handler.MessageEventHandler
import toby.handler.StartUpHandler
import toby.handler.VoiceEventHandler
import toby.jpa.service.IConfigService
import toby.jpa.service.IUserService
import toby.managers.ButtonManager
import toby.managers.CommandManager
import toby.managers.MenuManager

@Service
@Configurable
open class BotMain @Autowired constructor(
    jda: JDA,
    configService: IConfigService,
    userService: IUserService,
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
        jda.addEventListener(VoiceEventHandler(jda, configService, userService))
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