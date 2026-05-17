package bot.toby.handler

import common.logging.DiscordLogger
import core.managers.CommandManager
import database.service.XpAwardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import kotlin.coroutines.CoroutineContext

@Service
class SlashCommandEventListener @Autowired constructor(
    private val commandManager: CommandManager,
    private val xpAwardService: XpAwardService,
) : ListenerAdapter(), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        logger.setGuildAndMemberContext(event.guild, event.member)
        logger.info { "SlashCommandInteractionEvent '${event.name}' received" }
        if (event.user.isBot) return
        val guildId = event.guild?.idLong
        val channelId = event.channel?.idLong
        launch {
            logger.info { "Launching coroutine for '${event.name}'" }
            commandManager.handle(event)
        }.invokeOnCompletion {
            logger.info { "Finished coroutine for '${event.name}'" }
            if (guildId != null) {
                xpAwardService.award(
                    discordId = event.user.idLong,
                    guildId = guildId,
                    amount = COMMAND_XP,
                    reason = "slash-command:${event.name}",
                    channelId = channelId
                )
            }
        }
    }

    companion object {
        // Small XP grant per slash-command invocation. The daily XP cap
        // (default 1000) prevents spam-farming while still letting active
        // users meaningfully progress from command usage alone.
        const val COMMAND_XP: Long = 5L
    }
}
