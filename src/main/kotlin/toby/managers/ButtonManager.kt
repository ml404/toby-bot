package toby.managers

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import org.springframework.beans.factory.annotation.Configurable
import org.springframework.stereotype.Service
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.command.commands.misc.RollCommand
import toby.helpers.DnDHelper
import toby.helpers.MusicPlayerHelper
import toby.helpers.UserDtoHelper
import toby.jpa.dto.ConfigDto
import toby.jpa.service.IConfigService
import toby.jpa.service.IUserService
import toby.lavaplayer.PlayerManager
import java.util.*

@Service
@Configurable
class ButtonManager(
    private val configService: IConfigService,
    private val userService: IUserService,
    private val commandManager: CommandManager
) {

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

        // Dispatch the simulated SlashCommandInteractionEvent
        val componentId = event.componentId
        event.channel.sendTyping().queue()

        if (componentId == "resend_last_request") {
            val (cmd, ctx) = commandManager.lastCommands[event.user] ?: return
            cmd.handle(ctx, requestingUserDto, deleteDelay)
            event.deferEdit().queue()
        } else {
            val hook = event.hook
            val musicManager = PlayerManager.instance.getMusicManager(guild)

            when (componentId) {
                "pause/play" -> MusicPlayerHelper.changePauseStatusOnTrack(event, musicManager, deleteDelay)
                "stop" -> MusicPlayerHelper.stopSong(event, musicManager, requestingUserDto.superUser, deleteDelay)
                "init:next" -> DnDHelper.incrementTurnTable(hook, event, deleteDelay)
                "init:prev" -> DnDHelper.decrementTurnTable(hook, event, deleteDelay)
                "init:clear" -> DnDHelper.clearInitiative(hook, event)
                else -> {
                    val (commandName, options) = componentId.split(":").takeIf { it.size == 2 } ?: return
                    val cmd = commandManager.getCommand(commandName.lowercase(Locale.getDefault())) ?: return

                    event.channel.sendTyping().queue()
                    if (cmd.name == "roll") {
                        val rollCommand = cmd as? RollCommand ?: return
                        val optionArray = options.split(",").mapNotNull { it.toIntOrNull() }.toTypedArray()
                        if (optionArray.size == 3) {
                            rollCommand.handleDiceRoll(
                                event,
                                optionArray[0],
                                optionArray[1],
                                optionArray[2]
                            ).queue { invokeDeleteOnMessageResponse(deleteDelay) }
                        }
                    } else {
                        val commandContext = CommandContext(event)
                        cmd.handle(commandContext, requestingUserDto, deleteDelay)
                    }
                }
            }
        }
    }
}