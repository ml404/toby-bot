package bot.toby.managers

import bot.toby.modal.DefaultModalContext
import common.logging.DiscordLogger
import core.managers.ModalManager
import core.modal.Modal
import database.dto.guild.ConfigDto
import database.service.guild.ConfigService
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable

@Configurable
class DefaultModalManager @Autowired constructor(
    private val configService: ConfigService,
    override val modals: List<Modal>
) : ModalManager {
    private val logger: DiscordLogger = DiscordLogger.createLogger(this::class.java)

    override fun getModal(search: String): Modal? {
        val prefix = search.substringBefore(':').lowercase()
        return modals.find { it.name.equals(prefix, true) }
    }

    override fun handle(event: ModalInteractionEvent) {
        val guild = event.guild ?: return
        val modal = getModal(event.modalId) ?: run {
            logger.warn("No modal handler for '${event.modalId}'")
            return
        }
        val deleteDelay = configService.getConfigByName(
            ConfigDto.Configurations.DELETE_DELAY.configValue,
            guild.id
        )?.value?.toIntOrNull() ?: 0
        val ctx = DefaultModalContext(event)
        modal.handle(ctx, deleteDelay)
    }
}
