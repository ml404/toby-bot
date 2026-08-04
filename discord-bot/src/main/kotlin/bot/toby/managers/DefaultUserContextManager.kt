package bot.toby.managers

import bot.toby.helpers.UserDtoHelper
import common.logging.DiscordLogger
import core.command.UserContextCommand
import core.managers.UserContextManager
import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable

@Configurable
class DefaultUserContextManager @Autowired constructor(
    private val configService: ConfigService,
    private val userDtoHelper: UserDtoHelper,
    override val userContextCommands: List<UserContextCommand>
) : UserContextManager {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun handle(event: UserContextInteractionEvent) {
        val guild = event.guild ?: return
        val command = getUserContextCommand(event.name) ?: run {
            logger.warn("No user context handler for '${event.name}'")
            return
        }

        logger.setGuildAndMemberContext(guild, event.member)

        // Defer before the DB lookups below so a slow query can't eat the
        // 3-second ack window — same reasoning as DefaultMessageContextManager.
        if (command.defersReply) event.deferReply(command.ephemeral).queue()

        val deleteDelay = configService.getConfigByName(
            ConfigDto.Configurations.DELETE_DELAY.configValue,
            guild.id
        )?.value?.toIntOrNull() ?: 0
        val requestingUserDto = event.member?.let {
            userDtoHelper.calculateUserDto(event.user.idLong, guild.idLong, it.isOwner)
        } ?: return

        logger.info { "Handling user context command: ${command.name}" }
        command.handle(event, requestingUserDto, deleteDelay)
    }
}
