package bot.toby.managers

import bot.toby.button.DefaultButtonContext
import bot.toby.helpers.UserDtoHelper
import core.button.Button
import core.managers.ButtonManager
import database.dto.ConfigDto
import database.service.ConfigService
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable

@Configurable
class DefaultButtonManager @Autowired constructor(
    private val configService: ConfigService,
    private val userDtoHelper: UserDtoHelper,
    override val buttons: List<Button>
) : ButtonManager {

    override fun handle(event: ButtonInteractionEvent) {
        val guild = event.guild ?: return
        val guildId = guild.idLong
        val deleteDelay = configService.getConfigByName(
            ConfigDto.Configurations.DELETE_DELAY.configValue,
            guild.id
        )?.value?.toIntOrNull() ?: 0
        val requestingUserDto = event.member?.let {
            userDtoHelper.calculateUserDto(event.user.idLong, guildId, it.isOwner)
        } ?: return


        val btn = getButton(event.componentId.lowercase())

        btn?.let {
            event.channel.sendTyping().queue()
            val ctx = DefaultButtonContext(event)
            requestingUserDto.let { userDto -> it.handle(ctx, userDto, deleteDelay) }
        }
    }

    override fun getButton(search: String): Button? = buttons.find { it.name.equals(search, true) }
}