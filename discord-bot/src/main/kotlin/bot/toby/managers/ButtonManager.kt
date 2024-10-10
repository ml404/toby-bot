package bot.toby.managers

import bot.toby.button.ButtonContext
import bot.toby.button.IButton
import bot.toby.button.buttons.*
import bot.toby.helpers.Cache
import bot.toby.helpers.DnDHelper
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
    dndHelper: DnDHelper,
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
        addButton(InitiativeNextButton(dndHelper))
        addButton(InitiativePreviousButton(dndHelper))
        addButton(InitiativeClearButton(dndHelper))
    }

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


    private fun addButton(btn: IButton) {
        val nameFound = buttons.any { it.name.equals(btn.name, true) }
        require(!nameFound) { "A button with this name is already present" }
        buttons.add(btn)
    }

    private fun getButton(search: String): IButton? = buttons.find { it.name.equals(search, true) }
}