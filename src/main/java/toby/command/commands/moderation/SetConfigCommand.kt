package toby.command.commands.moderation

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import toby.command.CommandContext
import toby.command.ICommand.Companion.invokeDeleteOnMessageResponse
import toby.jpa.dto.ConfigDto
import toby.jpa.dto.UserDto
import toby.jpa.service.IConfigService
import java.util.*
import java.util.function.Consumer

class SetConfigCommand(private val configService: IConfigService) : IModerationCommand {
    override fun handle(ctx: CommandContext?, requestingUserDto: UserDto, deleteDelay: Int?) {
        val event = ctx!!.event
        event.deferReply().queue()
        val member = ctx.member
        if (!member!!.isOwner) {
            event.hook.sendMessage("This is currently reserved for the owner of the server only, this may change in future").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        validateArgumentsAndUpdateConfigs(event, deleteDelay)
    }

    private fun validateArgumentsAndUpdateConfigs(event: SlashCommandInteractionEvent, deleteDelay: Int?) {
        val options = event.options
        if (options.isEmpty()) {
            event.hook.sendMessage(description).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        options.forEach(Consumer { optionMapping: OptionMapping ->
            when (ConfigDto.Configurations.valueOf(optionMapping.name.uppercase(Locale.getDefault()))) {
                ConfigDto.Configurations.MOVE -> setMove(event, deleteDelay)
                ConfigDto.Configurations.VOLUME -> setConfigAndSendMessage(event, optionMapping, deleteDelay, "Set default volume to '%s'")
                ConfigDto.Configurations.DELETE_DELAY -> setConfigAndSendMessage(event, optionMapping, deleteDelay, "Set default delete message delay for TobyBot music messages to '%d' seconds")
                else -> {}
            }
        })
    }

    private fun setConfigAndSendMessage(event: SlashCommandInteractionEvent, optionMapping: OptionMapping, deleteDelay: Int?, messageToSend: String) {
        val newValueOptional = Optional.ofNullable(optionMapping).map { obj: OptionMapping -> obj.asInt }
        if (newValueOptional.isEmpty || newValueOptional.get() < 0) {
            event.hook.sendMessage("Value given invalid (a whole number representing percent)").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
            return
        }
        val configValue = ConfigDto.Configurations.valueOf(optionMapping.name.uppercase(Locale.getDefault())).configValue
        val databaseConfig = configService.getConfigByName(configValue, event.guild!!.id)
        val newDefaultVolume = optionMapping.asInt
        val newConfigDto = ConfigDto(configValue, newDefaultVolume.toString(), event.guild!!.id)
        if (databaseConfig != null && databaseConfig.guildId == newConfigDto.guildId) {
            configService.updateConfig(newConfigDto)
        } else {
            configService.createNewConfig(newConfigDto)
        }
        event.hook.sendMessageFormat(messageToSend, newDefaultVolume).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
    }

    private fun setMove(event: SlashCommandInteractionEvent, deleteDelay: Int?) {
        val movePropertyName = ConfigDto.Configurations.MOVE.configValue
        val newDefaultMoveChannelOptional = Optional.ofNullable(event.getOption(ConfigDto.Configurations.MOVE.name.lowercase(Locale.getDefault()))).map { obj: OptionMapping -> obj.getAsChannel() }
        if (newDefaultMoveChannelOptional.isPresent) {
            val databaseConfig = configService.getConfigByName(movePropertyName, event.guild!!.id)
            val newChannel = newDefaultMoveChannelOptional.get()
            val newConfigDto = ConfigDto(movePropertyName, newChannel.name, event.guild!!.id)
            if (databaseConfig != null && databaseConfig.guildId == newConfigDto.guildId) {
                configService.updateConfig(newConfigDto)
            } else {
                configService.createNewConfig(newConfigDto)
            }
            event.hook.sendMessageFormat("Set default move channel to '%s'", newChannel.name).setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        } else {
            event.hook.sendMessage("No valid channel was mentioned, so config was not updated").setEphemeral(true).queue(invokeDeleteOnMessageResponse(deleteDelay!!))
        }
    }

    override val name: String
        get() = "setconfig"
    override val description: String
        get() = "Use this command to set the configuration used for this bot your server"
    override val optionData: List<OptionData>
        get() {
            val defaultVolume = OptionData(OptionType.INTEGER, ConfigDto.Configurations.VOLUME.name.lowercase(Locale.getDefault()), "Default volume for audio player on your server (100 without an override)", false)
            val deleteDelay = OptionData(OptionType.INTEGER, ConfigDto.Configurations.DELETE_DELAY.name.lowercase(Locale.getDefault()), "The length of time in seconds before your slash command messages are deleted", false)
            val channelValue = OptionData(OptionType.CHANNEL, ConfigDto.Configurations.MOVE.name.lowercase(Locale.getDefault()), "Value for the default move channel you want if using move command without arguments", false)
            return listOf(defaultVolume, deleteDelay, channelValue)
        }
}
