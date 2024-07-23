package toby.managers

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.button.ButtonContext
import toby.button.IButton
import toby.button.buttons.*
import toby.helpers.Cache
import toby.helpers.UserDtoHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.service.IConfigService
import toby.jpa.service.IUserService
import java.util.*

@Service
@Configurable
class ButtonManager @Autowired constructor(
    private val configService: IConfigService,
    private val userService: IUserService,
    commandManager: CommandManager
) {
    private val buttons: MutableList<IButton> = ArrayList()

    val allButtons: List<IButton> get() = buttons

    init {
        Cache(86400, 3600, 2)

        //music buttons
        addButton(PausePlayButton())
        addButton(StopButton())

        //MiscButtons
        addButton(ResendLastRequestButton(commandManager))
        addButton(RollButton(commandManager))

        //DnD Buttons
        addButton(InitiativeNextButton())
        addButton(InitiativePreviousButton())
        addButton(InitiativeClearButton())
    }

    fun handle(event: ButtonInteractionEvent) {
        val guild = event.guild ?: return
        val guildId = guild.idLong
        val deleteDelay = configService.getConfigByName(
            ConfigDto.Configurations.DELETE_DELAY.configValue,
            guild.id
        )?.value?.toIntOrNull() ?: 0
        val volumePropertyName = ConfigDto.Configurations.VOLUME.configValue
        val defaultVolume = configService.getConfigByName(volumePropertyName, guild.id)?.value?.toIntOrNull() ?: 0

        val requestingUserDto = event.member?.let {
            UserDtoHelper.calculateUserDto(guildId, event.user.idLong, it.isOwner, userService, defaultVolume)
        } ?: return


        val btn = getButton(event.componentId.lowercase())

        btn?.let {
            event.channel.sendTyping().queue()
            val ctx = ButtonContext(event)
            requestingUserDto.let { userDto -> it.handle(ctx, userDto, deleteDelay) }
        }
    }


    private fun addButton(btn: IButton) {
        val nameFound = buttons.any { it.name.equals(btn.name, true) }
        require(!nameFound) { "A button with this name is already present" }
        buttons.add(btn)
    }

    private fun getButton(search: String): IButton? = buttons.find { it.name.equals(search, true) }
}