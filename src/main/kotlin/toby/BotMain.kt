package toby

import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.handler.MessageEventHandler
import toby.handler.StartUpHandler
import toby.handler.VoiceEventHandler
import toby.helpers.HttpHelper
import toby.jpa.service.*

@Service
@Configurable
open class BotMain @Autowired constructor(
    jda: JDA,
    configService: IConfigService,
    brotherService: IBrotherService,
    userService: IUserService,
    musicFileService: IMusicFileService,
    excuseService: IExcuseService,
    httpHelper: HttpHelper
) {
    init {
        jda.addEventListener(StartUpHandler(
            jda,
            configService,
            brotherService,
            userService,
            musicFileService,
            excuseService,
            httpHelper
        ))
        jda.addEventListener(VoiceEventHandler(jda, configService, userService))
        jda.addEventListener(MessageEventHandler(jda,
            configService,
            brotherService,
            userService,
            musicFileService,
            excuseService,
            httpHelper))
    }
}