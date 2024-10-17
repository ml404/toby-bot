package bot.toby.managers

import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.helpers.UserDtoHelper
import database.dto.ConfigDto
import database.service.IConfigService
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable

@Configurable
class ButtonManager @Autowired constructor(
    private val configService: IConfigService,
    private val userDtoHelper: UserDtoHelper,
    val buttons: List<IButton>
) {

    fun handle(event: ButtonInteractionEvent) {
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
            val ctx = ButtonContext(event)
            requestingUserDto.let { userDto -> it.handle(ctx, userDto, deleteDelay) }
        }
    }

    private fun getButton(search: String): IButton? = buttons.find { it.name.equals(search, true) }
}