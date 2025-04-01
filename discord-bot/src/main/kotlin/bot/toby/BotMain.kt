package bot.toby

import bot.toby.handler.EventWaiter
import bot.toby.handler.MessageEventHandler
import bot.toby.handler.StartUpHandler
import bot.toby.handler.VoiceEventHandler
import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
open class BotMain @Autowired constructor(
    jda: JDA,
    eventWaiter: EventWaiter,
    startUpHandler: StartUpHandler,
    voiceEventHandler: VoiceEventHandler,
    messageEventHandler: MessageEventHandler,
) {
    init {
        jda.addEventListener(startUpHandler, voiceEventHandler, messageEventHandler, eventWaiter)
    }
}