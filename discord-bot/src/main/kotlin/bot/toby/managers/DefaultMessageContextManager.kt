package bot.toby.managers

import bot.toby.helpers.UserDtoHelper
import common.logging.DiscordLogger
import core.command.MessageContextCommand
import core.managers.MessageContextManager
import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable

@Configurable
class DefaultMessageContextManager @Autowired constructor(
    private val configService: ConfigService,
    private val userDtoHelper: UserDtoHelper,
    override val messageContextCommands: List<MessageContextCommand>
) : MessageContextManager {

    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun handle(event: MessageContextInteractionEvent) {
        val guild = event.guild ?: return
        val command = getMessageContextCommand(event.name) ?: run {
            logger.warn("No message context handler for '${event.name}'")
            return
        }

        logger.setGuildAndMemberContext(guild, event.member)

        // Defer before the DB lookups below so a slow query can't eat the
        // 3-second ack window — same reasoning as DefaultCommandManager.
        // Commands that open a modal opt out: a modal must be the first
        // response, so deferring would make `replyModal` impossible.
        if (command.defersReply) event.deferReply(command.ephemeral).queue()

        val deleteDelay = configService.getConfigByName(
            ConfigDto.Configurations.DELETE_DELAY.configValue,
            guild.id
        )?.value?.toIntOrNull() ?: 0
        val requestingUserDto = event.member?.let {
            userDtoHelper.calculateUserDto(event.user.idLong, guild.idLong, it.isOwner)
        } ?: return

        logger.info { "Handling message context command: ${command.name}" }
        command.handle(event, requestingUserDto, deleteDelay)
    }
}
