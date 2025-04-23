package bot.configuration

import bot.toby.handler.EventWaiter
import bot.toby.handler.MessageEventHandler
import bot.toby.handler.StartUpHandler
import bot.toby.handler.VoiceEventHandler
import net.dv8tion.jda.api.JDA
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
class JdaListenerRegistrar @Autowired constructor(
    jda: JDA,
    startUpHandler: StartUpHandler,
    voiceEventHandler: VoiceEventHandler,
    messageEventHandler: MessageEventHandler,
    eventWaiter: EventWaiter
) {
    init {
        jda.addEventListener(
            startUpHandler,
            voiceEventHandler,
            messageEventHandler,
            eventWaiter
        )
    }
}