package toby.handler

import mu.KotlinLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.helpers.HttpHelper
import toby.jpa.service.*
import toby.managers.CommandManager

@Service
@Configurable
class StartUpHandler @Autowired constructor(
    private val jda: JDA,
    private val configService: IConfigService,
    brotherService: IBrotherService,
    private val userService: IUserService,
    musicFileService: IMusicFileService,
    excuseService: IExcuseService,
    httpHelper: HttpHelper,
    private val commandManager: CommandManager = CommandManager(
        configService,
        brotherService,
        userService,
        musicFileService,
        excuseService,
        httpHelper
    )
) : ListenerAdapter() {

    private val logger = KotlinLogging.logger {}

    override fun onReady(event: ReadyEvent) {
        logger.info("${event.jda.selfUser.name} is ready")
        jda.updateCommands().addCommands(commandManager.allSlashCommands).queue()
    }
}
